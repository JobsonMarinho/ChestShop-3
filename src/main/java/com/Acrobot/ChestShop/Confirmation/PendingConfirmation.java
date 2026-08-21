package com.Acrobot.ChestShop.Confirmation;

import com.Acrobot.ChestShop.Events.TransactionEvent.TransactionType;
import org.bukkit.Location;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;

/**
 * A transaction that a player has been asked to confirm in the confirmation menu.
 *
 * Everything stored here is a snapshot taken <b>for display purposes only</b>. None of it is ever
 * used to actually move items or money - when the player accepts, the offer is rebuilt from the
 * world and validated from scratch, and this snapshot is only compared against the fresh one so
 * that the player can never be charged for something different from what they were shown.
 *
 * That distinction is the whole reason this class exists: replaying a cached stock/price is exactly
 * how a confirmation menu turns into an item duplication bug.
 *
 * @author Acrobot
 */
public class PendingConfirmation {
    private final UUID clientId;
    private final Location signLocation;
    private final String[] signLines;

    private final TransactionType transactionType;
    private final boolean adminShop;
    private final boolean sneaking;

    private final String ownerName;
    private final ItemStack[] stock;
    private final double price;
    private final int itemAmount;

    private final long creationTime;

    private int timeoutTaskId = -1;

    public PendingConfirmation(UUID clientId, Location signLocation, String[] signLines, TransactionType transactionType,
                               boolean adminShop, boolean sneaking, String ownerName, ItemStack[] stock, double price, int itemAmount) {
        this.clientId = clientId;
        this.signLocation = signLocation;
        this.signLines = signLines;
        this.transactionType = transactionType;
        this.adminShop = adminShop;
        this.sneaking = sneaking;
        this.ownerName = ownerName;
        this.stock = stock;
        this.price = price;
        this.itemAmount = itemAmount;
        this.creationTime = System.currentTimeMillis();
    }

    public UUID getClientId() {
        return clientId;
    }

    /**
     * @return Location of the shop sign - the sign's state is deliberately not kept, as a
     *         {@link org.bukkit.block.Sign} snapshot would keep serving the lines it had when the menu opened
     */
    public Location getSignLocation() {
        return signLocation;
    }

    /**
     * @return The (colour stripped) sign lines as they were when the menu was opened
     */
    public String[] getSignLines() {
        return signLines;
    }

    public TransactionType getTransactionType() {
        return transactionType;
    }

    public boolean isAdminShop() {
        return adminShop;
    }

    /**
     * @return Whether the player was crouching when they clicked the sign (needed to rebuild the
     *         very same offer when SHIFT_SELLS_IN_STACKS is turned on)
     */
    public boolean isSneaking() {
        return sneaking;
    }

    public String getOwnerName() {
        return ownerName;
    }

    /**
     * @return A private copy of the items shown to the player - display and comparison only
     */
    public ItemStack[] getStock() {
        return stock;
    }

    public double getPrice() {
        return price;
    }

    public int getItemAmount() {
        return itemAmount;
    }

    public long getCreationTime() {
        return creationTime;
    }

    public int getTimeoutTaskId() {
        return timeoutTaskId;
    }

    public void setTimeoutTaskId(int timeoutTaskId) {
        this.timeoutTaskId = timeoutTaskId;
    }
}
