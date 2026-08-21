package com.Acrobot.ChestShop.Confirmation;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.lang.reflect.Method;

/**
 * Stores the tag in the item's real NBT compound, reached through reflection.
 *
 * The plugin is built against spigot-api alone, so NMS is not on the compile classpath - and even if
 * it were, importing net.minecraft.server.v1_8_R3 directly would pin the class to one server build
 * and make it fail to load anywhere else. The server package is therefore resolved at runtime and
 * the constructor fails fast, which lets {@link MenuItemGuard} fall back instead of taking the
 * plugin down with it.
 *
 * The method names used here (hasTag/getTag/setTag, setString/getString/hasKey) are stable from
 * 1.8 through 1.13.
 *
 * @author Acrobot
 */
class NmsItemTagger implements ItemTagger {
    private final Class<?> compound;

    private final Method asNMSCopy;
    private final Method asCraftMirror;
    private final Method hasTag;
    private final Method getTag;
    private final Method setTag;
    private final Method setString;
    private final Method getString;
    private final Method hasKey;

    NmsItemTagger() {
        String craftPackage = Bukkit.getServer().getClass().getPackage().getName();
        String version = craftPackage.substring(craftPackage.lastIndexOf('.') + 1);

        if (!version.startsWith("v1_")) {
            throw new IllegalStateException("CraftBukkit package carries no version: " + craftPackage);
        }

        try {
            String nmsPackage = "net.minecraft.server." + version;

            Class<?> craftItemStack = Class.forName(craftPackage + ".inventory.CraftItemStack");
            Class<?> nmsItemStack = Class.forName(nmsPackage + ".ItemStack");

            compound = Class.forName(nmsPackage + ".NBTTagCompound");

            asNMSCopy = craftItemStack.getMethod("asNMSCopy", ItemStack.class);
            asCraftMirror = craftItemStack.getMethod("asCraftMirror", nmsItemStack);

            hasTag = nmsItemStack.getMethod("hasTag");
            getTag = nmsItemStack.getMethod("getTag");
            setTag = nmsItemStack.getMethod("setTag", compound);

            setString = compound.getMethod("setString", String.class, String.class);
            getString = compound.getMethod("getString", String.class);
            hasKey = compound.getMethod("hasKey", String.class);
        } catch (Exception exception) {
            throw new IllegalStateException("NMS item tags are not available: " + exception.getMessage(), exception);
        }
    }

    public String getName() {
        return "NMS NBT (reflection)";
    }

    public ItemStack write(ItemStack stack, String key, String value) {
        if (stack == null || stack.getType() == Material.AIR) {
            return stack;
        }

        try {
            Object nmsStack = asNMSCopy.invoke(null, stack);
            if (nmsStack == null) {
                return stack;
            }

            Object tag = (Boolean) hasTag.invoke(nmsStack)
                    ? getTag.invoke(nmsStack)
                    : compound.getDeclaredConstructor().newInstance();

            setString.invoke(tag, key, value);
            setTag.invoke(nmsStack, tag);

            return (ItemStack) asCraftMirror.invoke(null, nmsStack);
        } catch (Exception exception) {
            // An unmarked menu item is a hole in the sweep, so let it be seen rather than swallowed
            throw new IllegalStateException("Could not tag a menu item", exception);
        }
    }

    public String read(ItemStack stack, String key) {
        if (stack == null || stack.getType() == Material.AIR) {
            return null;
        }

        try {
            Object nmsStack = asNMSCopy.invoke(null, stack);
            if (nmsStack == null || !(Boolean) hasTag.invoke(nmsStack)) {
                return null;
            }

            Object tag = getTag.invoke(nmsStack);
            if (tag == null || !(Boolean) hasKey.invoke(tag, key)) {
                return null;
            }

            return (String) getString.invoke(tag, key);
        } catch (Exception exception) {
            return null;
        }
    }

    public boolean has(ItemStack stack, String key) {
        String value = read(stack, key);

        return value != null && !value.isEmpty();
    }
}
