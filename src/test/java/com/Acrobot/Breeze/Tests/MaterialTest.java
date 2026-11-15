package com.Acrobot.Breeze.Tests;

import com.Acrobot.Breeze.Utils.MaterialUtil;
import com.Acrobot.Breeze.Utils.StringUtil;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Tests for {@link com.Acrobot.Breeze.Utils.MaterialUtil}
 *
 * @author Acrobot
 */
@RunWith(JUnit4.class)
public class MaterialTest {

    @Test
    public void testForBlank() {
        ItemStack air = new ItemStack(Material.AIR);

        assertTrue(MaterialUtil.isEmpty(air));
        assertTrue(MaterialUtil.isEmpty(null));
    }
    
    @Test
    public void testCodes() {
        for (Material material : Material.values()) {
            String shortenedName = MaterialUtil.getShortenedName(material.toString(), MaterialUtil.MAXIMUM_SIGN_WIDTH);
            assertSame(material, MaterialUtil.getMaterial(shortenedName));
        }
    }

    @Test
    public void testAlternativeFormCodes() {
        // getName(LOG_2:1) must not drop the "2", or the code resolves to LOG:1 (spruce)
        for (Material material : new Material[]{Material.LOG_2, Material.LEAVES_2}) {
            ItemStack parsed = MaterialUtil.getItem(StringUtil.capitalizeFirstLetter(material.toString(), '_') + ":1");
            assertSame(material, parsed.getType());
            assertSame((short) 1, parsed.getDurability());
        }
    }
}
