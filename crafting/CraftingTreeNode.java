package appeng.crafting;

import java.util.ArrayList;

import net.minecraft.world.World;
import appeng.api.config.Actionable;
import appeng.api.config.FuzzyMode;
import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.api.networking.security.BaseActionSource;
import appeng.api.storage.data.IAEItemStack;
import appeng.me.cache.CraftingCache;

public class CraftingTreeNode
{

	// parent node.
	private CraftingTreeProcess parent;
	private World world;

	// what slot!
	int slot;

	// what item is this?
	private IAEItemStack what;

	// what are the crafting patterns for this?
	private ArrayList<CraftingTreeProcess> nodes = new ArrayList();

	boolean cannotUse = false;
	long missing = 0;

	boolean sim;

	public CraftingTreeNode(CraftingCache cc, CraftingJob job, IAEItemStack wat, CraftingTreeProcess par, int slot, int depth) {
		what = wat;
		parent = par;
		this.slot = slot;
		this.world = job.jobHost.getWorld();
		sim = false;

		for (ICraftingPatternDetails details : cc.getCraftingFor( what ))// in order.
		{
			if ( notRecurive( details ) )
				nodes.add( new CraftingTreeProcess( cc, job, details, this, depth + 1, world ) );
		}
	}

	public IAEItemStack getStack(long size)
	{
		IAEItemStack is = what.copy();
		is.setStackSize( size );
		return is;
	}

	boolean notRecurive(ICraftingPatternDetails details)
	{
		IAEItemStack[] o = details.getOutputs();
		for (IAEItemStack i : o)
			if ( i.equals( what ) )
				return true;

		if ( parent == null )
			return false;

		return parent.notRecurive( details );
	}

	public IAEItemStack request(MECraftingInventory inv, long l, BaseActionSource src) throws CraftBranchFailure, InterruptedException
	{
		if ( Thread.interrupted() )
			throw new InterruptedException();

		what.setStackSize( l );
		if ( slot >= 0 && parent != null && parent.details.isCraftable() )
		{
			for (IAEItemStack fuzz : inv.getItemList().findFuzzy( what, FuzzyMode.IGNORE_ALL ))
			{
				if ( parent.details.isValidItemForSlot( slot, fuzz.getItemStack(), world ) )
				{
					fuzz = fuzz.copy();
					fuzz.setStackSize( l );
					IAEItemStack available = inv.extractItems( fuzz, Actionable.MODULATE, src );

					if ( available != null )
					{
						l -= available.getStackSize();

						if ( l == 0 )
							return available;
					}
				}
			}
		}
		else
		{
			IAEItemStack available = inv.extractItems( what, Actionable.MODULATE, src );

			if ( available != null )
			{
				l -= available.getStackSize();

				if ( l == 0 )
					return available;
			}
		}

		if ( nodes.size() == 1 )
		{
			CraftingTreeProcess pro = nodes.get( 0 );

			while (pro.possible && l > 0)
			{
				pro.request( inv, pro.getTimes( l, pro.getAmountCrafted( what ).getStackSize() ), src );

				what.setStackSize( l );
				IAEItemStack available = inv.extractItems( what, Actionable.MODULATE, src );

				if ( available != null )
				{
					l -= available.getStackSize();

					if ( l <= 0 )
						return available;
				}
				else
					pro.possible = false; // ;P
			}
		}
		else if ( nodes.size() > 1 )
		{
			for (CraftingTreeProcess pro : nodes)
			{
				try
				{
					while (pro.possible && l > 0)
					{
						MECraftingInventory subInv = new MECraftingInventory( inv );
						pro.request( subInv, 1, src );
						subInv.commit( src );

						what.setStackSize( l );
						IAEItemStack available = inv.extractItems( what, Actionable.MODULATE, src );

						if ( available != null )
						{
							l -= available.getStackSize();

							if ( l <= 0 )
								return available;
						}
						else
							pro.possible = false; // ;P
					}
				}
				catch (CraftBranchFailure fail)
				{
					pro.possible = true;
				}
			}
		}

		if ( sim )
		{
			missing += l;
			return what;
		}

		throw new CraftBranchFailure( what, l );
	}

	public void dive(CraftingJob job)
	{
		if ( missing > 0 )
			job.addMissing( getStack( missing ) );
		missing = 0;

		for (CraftingTreeProcess pro : nodes)
			pro.dive( job );
	}

	public void setSimulate()
	{
		sim = true;
		missing = 0;

		for (CraftingTreeProcess pro : nodes)
			pro.setSimulate();
	}
}