package mekanism.common.item;

import java.util.List;

import mekanism.api.energy.IEnergizedItem;
import mekanism.api.gas.Gas;
import mekanism.api.gas.GasStack;
import mekanism.api.gas.IGasItem;
import mekanism.common.Mekanism;
import mekanism.common.util.MekanismUtils;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;
import net.minecraftforge.fluids.FluidContainerRegistry;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.IFluidContainerItem;

public class ItemGaugeDropper extends ItemMekanism implements IGasItem, IFluidContainerItem
{
	public static int CAPACITY = FluidContainerRegistry.BUCKET_VOLUME;
	
	public static final int TRANSFER_RATE = 16;
	
	public ItemGaugeDropper()
	{
		super();
		setMaxStackSize(1);
		setMaxDamage(100);
		setNoRepair();
		setCreativeTab(Mekanism.tabMekanism);
	}
	
	private void updateDamage(ItemStack stack)
	{
		GasStack gas = getGas(stack);
		FluidStack fluid = getFluid(stack);
		
		if(gas == null && fluid == null)
		{
			stack.setItemDamage(100);
		}
		else if(gas != null)
		{
			stack.setItemDamage((int)Math.max(1, (Math.abs((((float)gas.amount/getMaxGas(stack))*100)-100))));
		}
		else if(fluid != null)
		{
			stack.setItemDamage((int)Math.max(1, (Math.abs((((float)fluid.amount/getCapacity(stack))*100)-100))));
		}
	}
	
	public ItemStack getEmptyItem()
	{
		ItemStack empty = new ItemStack(this);
		empty.setItemDamage(100);
		return empty;
	}
	
	@Override
	public void getSubItems(Item item, CreativeTabs tabs, List list)
	{
		list.add(getEmptyItem());
	}
	
	@Override
	public ItemStack onItemRightClick(ItemStack itemstack, World world, EntityPlayer entityplayer)
	{
		if(entityplayer.isSneaking())
		{
			setGas(itemstack, null);
			setFluid(itemstack, null);
		}
		
		return itemstack;
	}
	
	@Override
	public void addInformation(ItemStack itemstack, EntityPlayer entityplayer, List list, boolean flag)
	{
		GasStack gasStack = getGas(itemstack);
		FluidStack fluidStack = getFluid(itemstack);

		if(gasStack == null && fluidStack == null)
		{
			list.add(MekanismUtils.localize("gui.empty") + ".");
		}
		else if(gasStack != null)
		{
			list.add(MekanismUtils.localize("tooltip.stored") + " " + gasStack.getGas().getLocalizedName() + ": " + gasStack.amount);
		}
		else if(fluidStack != null)
		{
			list.add(MekanismUtils.localize("tooltip.stored") + " " + fluidStack.getFluid().getLocalizedName(fluidStack) + ": " + fluidStack.amount);
		}
	}

	@Override
	public FluidStack getFluid(ItemStack container) 
	{
		if(container.stackTagCompound == null)
		{
			return null;
		}

		if(container.stackTagCompound.hasKey("fluidStack"))
		{
			FluidStack stack = FluidStack.loadFluidStackFromNBT(container.stackTagCompound.getCompoundTag("fluidStack"));
			
			updateDamage(container);
			
			return stack;
		}
		
		updateDamage(container);
		
		return null;
	}
	
	public void setFluid(ItemStack container, FluidStack stack)
	{
		if(container.stackTagCompound == null)
		{
			container.setTagCompound(new NBTTagCompound());
		}
		
		if(stack == null || stack.amount == 0 || stack.fluidID == 0)
		{
			container.stackTagCompound.removeTag("fluidStack");
		}
		else {
			container.stackTagCompound.setTag("fluidStack", stack.writeToNBT(new NBTTagCompound()));
		}
		
		updateDamage(container);
	}

	@Override
	public int getCapacity(ItemStack container) 
	{
		return CAPACITY;
	}

	@Override
	public int fill(ItemStack container, FluidStack resource, boolean doFill) 
	{
		return 0;
	}

	@Override
	public FluidStack drain(ItemStack container, int maxDrain, boolean doDrain) 
	{
		return null;
	}

	@Override
	public int getRate(ItemStack itemstack) 
	{
		return TRANSFER_RATE;
	}

	@Override
	public int addGas(ItemStack itemstack, GasStack stack) 
	{
		return 0;
	}

	@Override
	public GasStack removeGas(ItemStack itemstack, int amount) 
	{
		if(getGas(itemstack) == null)
		{
			return null;
		}

		Gas type = getGas(itemstack).getGas();

		int gasToUse = Math.min(getStored(itemstack), Math.min(getRate(itemstack), amount));
		setGas(itemstack, new GasStack(type, getStored(itemstack)-gasToUse));

		return new GasStack(type, gasToUse);
	}

	private int getStored(ItemStack itemstack)
	{
		return getGas(itemstack) != null ? getGas(itemstack).amount : 0;
	}
	
	@Override
	public boolean canReceiveGas(ItemStack itemstack, Gas type) 
	{
		return false;
	}

	@Override
	public boolean canProvideGas(ItemStack itemstack, Gas type)
	{
		return getGas(itemstack) != null && (type == null || getGas(itemstack).getGas() == type);
	}

	@Override
	public GasStack getGas(ItemStack itemstack) 
	{
		if(itemstack.stackTagCompound == null)
		{
			return null;
		}

		GasStack stored = GasStack.readFromNBT(itemstack.stackTagCompound.getCompoundTag("gasStack"));

		updateDamage(itemstack);

		return stored;
	}

	@Override
	public void setGas(ItemStack itemstack, GasStack stack) 
	{
		if(itemstack.stackTagCompound == null)
		{
			itemstack.setTagCompound(new NBTTagCompound());
		}

		if(stack == null || stack.amount == 0)
		{
			itemstack.stackTagCompound.removeTag("gasStack");
		}
		else {
			int amount = Math.max(0, Math.min(stack.amount, getMaxGas(itemstack)));
			GasStack gasStack = new GasStack(stack.getGas(), amount);

			itemstack.stackTagCompound.setTag("gasStack", gasStack.write(new NBTTagCompound()));
		}
		
		updateDamage(itemstack);
	}

	@Override
	public int getMaxGas(ItemStack itemstack) 
	{
		return CAPACITY;
	}

	@Override
	public boolean isMetadataSpecific(ItemStack itemstack) 
	{
		return false;
	}
}