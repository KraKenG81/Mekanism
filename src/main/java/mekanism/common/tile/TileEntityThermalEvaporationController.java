package mekanism.common.tile;

import io.netty.buffer.ByteBuf;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

import mekanism.api.Coord4D;
import mekanism.api.ISalinationSolar;
import mekanism.api.MekanismConfig.general;
import mekanism.api.Range4D;
import mekanism.client.SparkleAnimation.INodeChecker;
import mekanism.common.Mekanism;
import mekanism.common.base.IActiveState;
import mekanism.common.base.ITankManager;
import mekanism.common.content.tank.TankUpdateProtocol;
import mekanism.common.network.PacketTileEntity.TileEntityMessage;
import mekanism.common.recipe.RecipeHandler;
import mekanism.common.recipe.RecipeHandler.Recipe;
import mekanism.common.recipe.inputs.FluidInput;
import mekanism.common.recipe.machines.ThermalEvaporationRecipe;
import mekanism.common.util.MekanismUtils;
import net.minecraft.block.Block;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.EnumFacing;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidContainerRegistry;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidTank;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

public class TileEntityThermalEvaporationController extends TileEntityThermalEvaporationBlock implements IActiveState, ITankManager
{
	public static final int MAX_OUTPUT = 10000;
	public static final int MAX_SOLARS = 4;
	public static final int MAX_HEIGHT = 18;

	public FluidTank inputTank = new FluidTank(0);
	public FluidTank outputTank = new FluidTank(MAX_OUTPUT);

	public Set<Coord4D> tankParts = new HashSet<Coord4D>();
	public ISalinationSolar[] solars = new ISalinationSolar[4];

	public boolean temperatureSet = false;
	
	public double partialInput = 0;
	public double partialOutput = 0;
	
	public float biomeTemp = 0;
	public float temperature = 0;
	public float heatToAbsorb = 0;
	
	public float lastGain = 0;
	
	public int height = 0;
	
	public boolean structured = false;
	public boolean controllerConflict = false;
	public boolean isLeftOnFace;
	
	public boolean updatedThisTick = false;

	public int clientSolarAmount;
	
	public boolean cacheStructure = false;
	
	public float prevScale;
	
	public TileEntityThermalEvaporationController()
	{
		super("ThermalEvaporationController");
		
		inventory = new ItemStack[4];
	}

	@Override
	public void onUpdate()
	{
		super.onUpdate();
		
		if(!worldObj.isRemote)
		{
			updatedThisTick = false;
			
			if(ticker == 5)
			{
				refresh();
			}
			
			if(structured)
			{
				updateTemperature();
			}
			
			manageBuckets();
			
			ThermalEvaporationRecipe recipe = getRecipe();
	
			if(canOperate(recipe))
			{
				int outputNeeded = outputTank.getCapacity()-outputTank.getFluidAmount();
				int inputStored = inputTank.getFluidAmount();
				
				double tempMult = Math.max(0, getTemperature())*general.evaporationTempMultiplier;
				double inputToUse = (tempMult*recipe.recipeInput.ingredient.amount)*((float)height/(float)MAX_HEIGHT);
				inputToUse = Math.min(inputTank.getFluidAmount(), inputToUse);
				
				lastGain = (float)inputToUse/(float)recipe.recipeInput.ingredient.amount;
				partialInput += inputToUse;
				
				if(partialInput >= 1)
				{
					int inputInt = (int)Math.floor(partialInput);
					inputTank.drain(inputInt, true);
					partialInput %= 1;
					partialOutput += ((double)inputInt)/recipe.recipeInput.ingredient.amount;
				}
				
				if(partialOutput >= 1)
				{
					int outputInt = (int)Math.floor(partialOutput);
					outputTank.fill(new FluidStack(recipe.recipeOutput.output.getFluid(), outputInt), true);
					partialOutput %= 1;
				}
			}
			else {
				lastGain = 0;
			}
			
			if(structured)
			{
				if(Math.abs((float)inputTank.getFluidAmount()/inputTank.getCapacity()-prevScale) > 0.01)
				{
					Mekanism.packetHandler.sendToReceivers(new TileEntityMessage(Coord4D.get(this), getNetworkedData(new ArrayList<Object>())), new Range4D(Coord4D.get(this)));
					prevScale = (float)inputTank.getFluidAmount()/inputTank.getCapacity();
				}
			}
		}
	}
	
	public ThermalEvaporationRecipe getRecipe()
	{
		return RecipeHandler.getThermalEvaporationRecipe(new FluidInput(inputTank.getFluid()));
	}
	
	@Override
	public void onChunkUnload()
	{
		super.onChunkUnload();
		
		refresh();
	}
	
	@Override
	public void onNeighborChange(Block block)
	{
		super.onNeighborChange(block);
		
		refresh();
	}
	
	public boolean hasRecipe(Fluid fluid)
	{
		if(fluid == null)
		{
			return false;
		}
		
		return Recipe.THERMAL_EVAPORATION_PLANT.containsRecipe(fluid);
	}
	
	protected void refresh()
	{
		if(!worldObj.isRemote)
		{
			if(!updatedThisTick)
			{
				boolean prev = structured;
				
				clearStructure();
				structured = buildStructure();
				
				if(structured != prev)
				{
					Mekanism.packetHandler.sendToReceivers(new TileEntityMessage(Coord4D.get(this), getNetworkedData(new ArrayList())), new Range4D(Coord4D.get(this)));
				}
				
				if(structured)
				{
					inputTank.setCapacity(getMaxFluid());
					
					if(inputTank.getFluid() != null)
					{
						inputTank.getFluid().amount = Math.min(inputTank.getFluid().amount, getMaxFluid());
					}
				}
				else {
					clearStructure();
				}
			}
		}
	}

	public boolean canOperate(ThermalEvaporationRecipe recipe)
	{
		if(!structured || height < 3 || height > MAX_HEIGHT || inputTank.getFluid() == null)
		{
			return false;
		}

		return recipe != null && recipe.canOperate(inputTank, outputTank);

	}
	
	private void manageBuckets()
	{
		if(inventory[2] != null)
		{
			if(outputTank.getFluid() != null && outputTank.getFluid().amount >= FluidContainerRegistry.BUCKET_VOLUME)
			{
				if(FluidContainerRegistry.isEmptyContainer(inventory[2]))
				{
					ItemStack tempStack = FluidContainerRegistry.fillFluidContainer(outputTank.getFluid(), inventory[2]);
					
					if(tempStack != null)
					{
						if(inventory[3] == null)
						{
							outputTank.drain(FluidContainerRegistry.BUCKET_VOLUME, true);
							
							inventory[3] = tempStack;
							inventory[2].stackSize--;
							
							if(inventory[2].stackSize <= 0)
							{
								inventory[2] = null;
							}
							
							markDirty();
						}
						else if(tempStack.isItemEqual(inventory[3]) && tempStack.getMaxStackSize() > inventory[3].stackSize)
						{
							outputTank.drain(FluidContainerRegistry.BUCKET_VOLUME, true);
							
							inventory[3].stackSize++;
							inventory[2].stackSize--;
							
							if(inventory[2].stackSize <= 0)
							{
								inventory[2] = null;
							}
							
							markDirty();
						}
					}
				}
			}
		}
		
		if(structured)
		{
			if(FluidContainerRegistry.isFilledContainer(inventory[0]))
			{
				FluidStack itemFluid = FluidContainerRegistry.getFluidForFilledItem(inventory[0]);
				
				if((inputTank.getFluid() == null && itemFluid.amount <= getMaxFluid()) || inputTank.getFluid().amount+itemFluid.amount <= getMaxFluid())
				{
					if(!hasRecipe(itemFluid.getFluid()) || (inputTank.getFluid() != null && !inputTank.getFluid().isFluidEqual(itemFluid)))
					{
						return;
					}
					
					ItemStack containerItem = inventory[0].getItem().getContainerItem(inventory[0]);
					
					boolean filled = false;
					
					if(containerItem != null)
					{
						if(inventory[1] == null || (inventory[1].isItemEqual(containerItem) && inventory[1].stackSize+1 <= containerItem.getMaxStackSize()))
						{
							inventory[0] = null;
							
							if(inventory[1] == null)
							{
								inventory[1] = containerItem;
							}
							else {
								inventory[1].stackSize++;
							}
							
							filled = true;
						}
					}
					else {						
						inventory[0].stackSize--;
						
						if(inventory[0].stackSize == 0)
						{
							inventory[0] = null;
						}
						
						filled = true;
					}
					
					if(filled)
					{
						inputTank.fill(itemFluid, true);
					}
				}
			}
		}
	}
	
	private void updateTemperature()
	{
		if(!temperatureSet)
		{
			biomeTemp = worldObj.getBiomeGenForCoordsBody(getPos()).getFloatTemperature(getPos());
			temperatureSet = true;
		}
		
		heatToAbsorb += getActiveSolars()*general.evaporationSolarMultiplier;
		temperature += heatToAbsorb/(float)height;
		
		float biome = biomeTemp-0.5F;
		float base = biome > 0 ? biome*20 : biomeTemp*40;
		float incr = (float)Math.sqrt(Math.abs(temperature-base))*(float)general.evaporationHeatDissipation;
		
		if(temperature > base)
		{
			incr = -incr;
		}
		
		temperature = (float)Math.min(general.evaporationMaxTemp, temperature + incr/(float)height);
		
		heatToAbsorb = 0;
		
		MekanismUtils.saveChunk(this);
	}
	
	public float getTemperature()
	{
		return temperature;
	}
	
	public int getActiveSolars()
	{
		if(worldObj.isRemote)
		{
			return clientSolarAmount;
		}
		
		int ret = 0;
		
		for(ISalinationSolar solar : solars)
		{
			if(solar != null && solar.seesSun())
			{
				ret++;
			}
		}
		
		return ret;
	}

	public boolean buildStructure()
	{
		EnumFacing right = MekanismUtils.getRight(facing);
		EnumFacing left = MekanismUtils.getLeft(facing);

		height = 0;
		controllerConflict = false;
		updatedThisTick = true;
		
		Coord4D startPoint = Coord4D.get(this);
		
		while(startPoint.offset(EnumFacing.UP).getTileEntity(worldObj) instanceof TileEntityThermalEvaporationBlock)
		{
			startPoint = startPoint.offset(EnumFacing.UP);
		}
		
		Coord4D test = startPoint.offset(EnumFacing.DOWN).offset(right, 2);
		isLeftOnFace = test.getTileEntity(worldObj) instanceof TileEntityThermalEvaporationBlock;
		
		startPoint = startPoint.offset(left, isLeftOnFace ? 1 : 2);
		
		if(!scanTopLayer(startPoint))
		{
			return false;
		}

		height = 1;
		
		Coord4D middlePointer = startPoint.offset(EnumFacing.DOWN);
		
		while(scanLowerLayer(middlePointer))
		{
			middlePointer = middlePointer.offset(EnumFacing.DOWN);
		}
		
		if(height < 3 || height > MAX_HEIGHT)
		{
			height = 0;
			return false;
		}

		structured = true;
		
		markDirty();
		
		return true;
	}

	public boolean scanTopLayer(Coord4D current)
	{
		EnumFacing right = MekanismUtils.getRight(facing);
		EnumFacing back = MekanismUtils.getBack(facing);

		for(int x = 0; x < 4; x++)
		{
			for(int z = 0; z < 4; z++)
			{
				Coord4D pointer = current.offset(right, x).offset(back, z);
				TileEntity pointerTile = pointer.getTileEntity(worldObj);
				
				int corner = getCorner(x, z);
				
				if(corner != -1)
				{
					if(addSolarPanel(pointer.getTileEntity(worldObj), corner))
					{
						continue;
					}
					else if(pointer.offset(EnumFacing.UP).getTileEntity(worldObj) instanceof TileEntityThermalEvaporationBlock || !addTankPart(pointerTile))
					{
						return false;
					}
				}
				else {
					if((x == 1 || x == 2) && (z == 1 || z == 2))
					{
						if(!pointer.isAirBlock(worldObj))
						{
							return false;
						}
					}
					else {
						if(pointer.offset(EnumFacing.UP).getTileEntity(worldObj) instanceof TileEntityThermalEvaporationBlock || !addTankPart(pointerTile))
						{
							return false;
						}
					}
				}
			}
		}

		return true;
	}
	
	public int getMaxFluid()
	{
		return height*4*TankUpdateProtocol.FLUID_PER_TANK;
	}
	
	public int getCorner(int x, int z)
	{
		if(x == 0 && z == 0)
		{
			return 0;
		}
		else if(x == 0 && z == 3)
		{
			return 1;
		}
		else if(x == 3 && z == 0)
		{
			return 2;
		}
		else if(x == 3 && z == 3)
		{
			return 3;
		}
		
		return -1;
	}

	public boolean scanLowerLayer(Coord4D current)
	{
		EnumFacing right = MekanismUtils.getRight(facing);
		EnumFacing back = MekanismUtils.getBack(facing);
		
		boolean foundCenter = false;

		for(int x = 0; x < 4; x++)
		{
			for(int z = 0; z < 4; z++)
			{
				Coord4D pointer = current.offset(right, x).offset(back, z);
				TileEntity pointerTile = pointer.getTileEntity(worldObj);
				
				if((x == 1 || x == 2) && (z == 1 || z == 2))
				{
					if(pointerTile instanceof TileEntityThermalEvaporationBlock)
					{
						if(!foundCenter)
						{
							if(x == 1 && z == 1)
							{
								foundCenter = true;
							}
							else {
								height = -1;
								return false;
							}
						}
					}
					else {
						if(foundCenter || !pointer.isAirBlock(worldObj))
						{
							height = -1;
							return false;
						}
					}
				}
				else {
					if(!addTankPart(pointerTile)) 
					{
						height = -1;
						return false;
					}
				}
			}
		}

		height++;
		
		return !foundCenter;
	}

	public boolean addTankPart(TileEntity tile)
	{
		if(tile instanceof TileEntityThermalEvaporationBlock && (tile == this || !(tile instanceof TileEntityThermalEvaporationController)))
		{
			if(tile != this)
			{
				((TileEntityThermalEvaporationBlock)tile).addToStructure(Coord4D.get(this));
				tankParts.add(Coord4D.get(tile));
			}
			
			return true;
		}
		else {
			if(tile != this && tile instanceof TileEntityThermalEvaporationController)
			{
				controllerConflict = true;
			}
			
			return false;
		}
	}

	public boolean addSolarPanel(TileEntity tile, int i)
	{
		if(tile instanceof ISalinationSolar && !tile.isInvalid())
		{
			solars[i] = (ISalinationSolar)tile;
			return true;
		}
		else {
			return false;
		}
	}
	
	public int getScaledTempLevel(int i)
	{
		return (int)(i*Math.min(1, getTemperature()/general.evaporationMaxTemp));
	}
	
	public Coord4D getRenderLocation()
	{
		if(!structured)
		{
			return null;
		}
		
		EnumFacing right = MekanismUtils.getRight(facing);
		Coord4D startPoint = Coord4D.get(this).offset(right);
		startPoint = isLeftOnFace ? startPoint.offset(right) : startPoint;
		
		startPoint = startPoint.offset(right.getOpposite()).offset(MekanismUtils.getBack(facing)).add(0, -(height-2), 0);
		
		return startPoint;
	}
	
	@Override
	public void handlePacketData(ByteBuf dataStream)
	{
		super.handlePacketData(dataStream);
		
		if(dataStream.readBoolean())
		{
			inputTank.setFluid(new FluidStack(FluidRegistry.getFluid(ByteBufUtils.readUTF8String(dataStream)), dataStream.readInt()));
		}
		else {
			inputTank.setFluid(null);
		}
		
		if(dataStream.readBoolean())
		{
			outputTank.setFluid(new FluidStack(FluidRegistry.getFluid(ByteBufUtils.readUTF8String(dataStream)), dataStream.readInt()));
		}
		else {
			outputTank.setFluid(null);
		}
		
		boolean prev = structured;
		
		structured = dataStream.readBoolean();
		controllerConflict = dataStream.readBoolean();
		clientSolarAmount = dataStream.readInt();
		height = dataStream.readInt();
		temperature = dataStream.readFloat();
		biomeTemp = dataStream.readFloat();
		isLeftOnFace = dataStream.readBoolean();
		lastGain = dataStream.readFloat();
		
		if(structured != prev)
		{
			inputTank.setCapacity(getMaxFluid());
			worldObj.markBlockRangeForRenderUpdate(getPos(), getPos().add(1,1,1));
			
			if(structured)
			{
				Mekanism.proxy.doGenericSparkle(this, new INodeChecker() {
					@Override
					public boolean isNode(TileEntity tile)
					{
						return tile instanceof TileEntityThermalEvaporationBlock;
					}
				});
			}
		}
		
		MekanismUtils.updateBlock(worldObj, getPos());
	}
	
	@Override
	public ArrayList<Object> getNetworkedData(ArrayList<Object> data)
	{
		super.getNetworkedData(data);
		
		if(inputTank.getFluid() != null)
		{
			data.add(true);
			data.add(inputTank.getFluid().getFluid().getName());
			data.add(inputTank.getFluid().amount);
		}
		else {
			data.add(false);
		}
		
		if(outputTank.getFluid() != null)
		{
			data.add(true);
			data.add(outputTank.getFluid().getFluid().getName());
			data.add(outputTank.getFluid().amount);
		}
		else {
			data.add(false);
		}
		
		data.add(structured);
		data.add(controllerConflict);
		data.add(getActiveSolars());
		data.add(height);
		data.add(temperature);
		data.add(biomeTemp);
		data.add(isLeftOnFace);
		data.add(lastGain);
		
		return data;
	}
	
	@Override
    public void readFromNBT(NBTTagCompound nbtTags)
    {
        super.readFromNBT(nbtTags);

        inputTank.readFromNBT(nbtTags.getCompoundTag("waterTank"));
        outputTank.readFromNBT(nbtTags.getCompoundTag("brineTank"));
        
        temperature = nbtTags.getFloat("temperature");
        
        partialInput = nbtTags.getDouble("partialWater");
        partialOutput = nbtTags.getDouble("partialBrine");
    }

	@Override
    public void writeToNBT(NBTTagCompound nbtTags)
    {
        super.writeToNBT(nbtTags);
        
        nbtTags.setTag("waterTank", inputTank.writeToNBT(new NBTTagCompound()));
        nbtTags.setTag("brineTank", outputTank.writeToNBT(new NBTTagCompound()));
        
        nbtTags.setFloat("temperature", temperature);
        
        nbtTags.setDouble("partialWater", partialInput);
        nbtTags.setDouble("partialBrine", partialOutput);
    }
	
	@Override
	public boolean canSetFacing(int side)
	{
		return side != 0 && side != 1;
	}

	public void clearStructure()
	{
		for(Coord4D tankPart : tankParts)
		{
			TileEntity tile = tankPart.getTileEntity(worldObj);
			
			if(tile instanceof TileEntityThermalEvaporationBlock)
			{
				((TileEntityThermalEvaporationBlock)tile).controllerGone();
			}
		}
		
		tankParts.clear();
		solars = new ISalinationSolar[] {null, null, null, null};
	}
	
	@Override
	@SideOnly(Side.CLIENT)
	public AxisAlignedBB getRenderBoundingBox()
	{
		return INFINITE_EXTENT_AABB;
	}

	@Override
	public boolean getActive()
	{
		return structured;
	}

	@Override
	public void setActive(boolean active) {}

	@Override
	public boolean renderUpdate()
	{
		return true;
	}

	@Override
	public boolean lightUpdate()
	{
		return false;
	}
	
	@Override
	public Object[] getTanks() 
	{
		return new Object[] {inputTank, outputTank};
	}
}