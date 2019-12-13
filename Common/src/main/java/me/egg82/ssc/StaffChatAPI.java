package me.egg82.ssc;

public class StaffChatAPI {
    private static final StaffChatAPI api = new StaffChatAPI();

    private StaffChatAPI() { }

    public static StaffChatAPI getInstance() { return api; }

    public int getMaxLevel(GenericEnchantment enchantment, GenericEnchantableItem... items) throws APIException {
        if (enchantment == null) {
            throw new APIException(false, "enchantment cannot be null.");
        }

        if (items == null) {
            return -1;
        }

        int max = -1;
        for (GenericEnchantableItem item : items) {
            if (item == null) {
                continue;
            }

            max = Math.max(max, item.getEnchantmentLevel(enchantment));
        }
        return max;
    }

    public boolean anyHasEnchantment(GenericEnchantment enchantment, GenericEnchantableItem... items) throws APIException {
        if (enchantment == null) {
            throw new APIException(false, "enchantment cannot be null.");
        }

        if (items == null) {
            return false;
        }

        for (GenericEnchantableItem item : items) {
            if (item == null) {
                continue;
            }

            if (item.hasEnchantment(enchantment)) {
                return true;
            }
        }

        return false;
    }

    public boolean allHaveEnchantment(GenericEnchantment enchantment, GenericEnchantableItem... items) throws APIException {
        if (enchantment == null) {
            throw new APIException(false, "enchantment cannot be null.");
        }

        if (items == null) {
            return false;
        }

        for (GenericEnchantableItem item : items) {
            if (item == null) {
                continue;
            }

            if (!item.hasEnchantment(enchantment)) {
                return false;
            }
        }

        return true;
    }
}
