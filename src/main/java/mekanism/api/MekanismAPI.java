package mekanism.api;

import java.util.HashSet;
import java.util.Set;

import net.minecraft.block.Block;
import net.minecraft.item.Item;
import cpw.mods.fml.common.eventhandler.Event;
import net.minecraftforge.oredict.OreDictionary;

public class MekanismAPI
{
	//Add a BlockInfo value here if you don't want a certain block to be picked up by cardboard boxes
	private static Set<ItemInfo> cardboardBoxIgnore = new HashSet<ItemInfo>();

	public static boolean isBlockCompatible(Item item, int meta)
	{
		for(ItemInfo i : cardboardBoxIgnore)
		{
			if(i.block == Block.getBlockFromItem(item) && (i.meta == OreDictionary.WILDCARD_VALUE || i.meta == meta))
			{
				return false;
			}
		}

		return true;
	}

	public static void addBoxBlacklist(Block block, int meta)
	{
		cardboardBoxIgnore.add(new ItemInfo(block, meta));
	}

	public static void removeBoxBlacklist(Block block, int meta)
	{
		cardboardBoxIgnore.remove(new ItemInfo(block, meta));
	}

	public static Set<ItemInfo> getBoxIgnore()
	{
		return cardboardBoxIgnore;
	}

	public static class BoxBlacklistEvent extends Event {}
}