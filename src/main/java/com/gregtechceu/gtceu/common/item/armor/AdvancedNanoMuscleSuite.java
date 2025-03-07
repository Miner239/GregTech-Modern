package com.gregtechceu.gtceu.common.item.armor;

import com.gregtechceu.gtceu.GTCEu;
import com.gregtechceu.gtceu.api.capability.GTCapabilityHelper;
import com.gregtechceu.gtceu.api.capability.IElectricItem;
import com.gregtechceu.gtceu.api.item.armor.ArmorComponentItem;
import com.gregtechceu.gtceu.api.item.armor.ArmorUtils;
import com.gregtechceu.gtceu.utils.input.KeyBind;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.NonNullList;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import com.mojang.datafixers.util.Pair;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Iterator;
import java.util.List;

public class AdvancedNanoMuscleSuite extends NanoMuscleSuite implements IJetpack {

    // A replacement for checking the current world time, to get around the gamerule that stops it
    private long timer = 0L;
    private List<Pair<NonNullList<ItemStack>, List<Integer>>> inventoryIndexMap;

    public AdvancedNanoMuscleSuite(int energyPerUse, long capacity, int tier) {
        super(ArmorItem.Type.CHESTPLATE, energyPerUse, capacity, tier);
    }

    @Override
    public void onArmorTick(Level world, Player player, @NotNull ItemStack item) {
        IElectricItem cont = GTCapabilityHelper.getElectricItem(item);
        if (cont == null) {
            return;
        }

        CompoundTag data = item.getOrCreateTag();
        boolean hoverMode = data.contains("hover") && data.getBoolean("hover");
        byte toggleTimer = data.contains("toggleTimer") ? data.getByte("toggleTimer") : 0;
        boolean canShare = data.contains("canShare") && data.getBoolean("canShare");

        if (toggleTimer == 0 && KeyBind.ARMOR_HOVER.isKeyDown(player)) {
            hoverMode = !hoverMode;
            toggleTimer = 5;
            data.putBoolean("hover", hoverMode);
            if (!world.isClientSide) {
                if (hoverMode)
                    player.displayClientMessage(Component.translatable("metaarmor.jetpack.hover.enable"), true);
                else
                    player.displayClientMessage(Component.translatable("metaarmor.jetpack.hover.disable"), true);
            }
        }

        if (toggleTimer == 0 && KeyBind.ARMOR_CHARGING.isKeyDown(player)) {
            canShare = !canShare;
            toggleTimer = 5;
            if (!world.isClientSide) {
                if (canShare && cont.getCharge() == 0)
                    player.displayClientMessage(Component.translatable("metaarmor.nms.share.error"), true);
                else if (canShare)
                    player.displayClientMessage(Component.translatable("metaarmor.nms.share.enable"), true);
                else
                    player.displayClientMessage(Component.translatable("metaarmor.nms.share.disable"), true);
            }

            // Only allow for charging to be enabled if charge is nonzero
            canShare = canShare && (cont.getCharge() != 0);
            data.putBoolean("canShare", canShare);
        }

        performFlying(player, hoverMode, item);

        // Charging mechanics
        if (canShare && !world.isClientSide) {
            // Check for new things to charge every 5 seconds
            if (timer % 100 == 0)
                inventoryIndexMap = ArmorUtils.getChargeableItem(player, cont.getTier());

            if (inventoryIndexMap != null && !inventoryIndexMap.isEmpty()) {
                // Charge all inventory slots
                for (int i = 0; i < inventoryIndexMap.size(); i++) {
                    Pair<NonNullList<ItemStack>, List<Integer>> inventoryMap = inventoryIndexMap.get(i);
                    Iterator<Integer> inventoryIterator = inventoryMap.getSecond().iterator();
                    while (inventoryIterator.hasNext()) {
                        int slot = inventoryIterator.next();
                        IElectricItem chargable = GTCapabilityHelper.getElectricItem(inventoryMap.getFirst().get(slot));

                        // Safety check the null, it should not actually happen. Also don't try and charge itself
                        if (chargable == null || chargable == cont) {
                            inventoryIterator.remove();
                            continue;
                        }

                        long attemptedChargeAmount = chargable.getTransferLimit() * 10;

                        // Accounts for tick differences when charging items
                        if (chargable.getCharge() < chargable.getMaxCharge() && cont.canUse(attemptedChargeAmount) &&
                                timer % 10 == 0) {
                            long delta = chargable.charge(attemptedChargeAmount, cont.getTier(), true, false);
                            if (delta > 0) {
                                cont.discharge(delta, cont.getTier(), true, false, false);
                            }
                            if (chargable.getCharge() == chargable.getMaxCharge()) {
                                inventoryIterator.remove();
                            }
                            player.inventoryMenu.sendAllDataToRemote();
                        }
                    }

                    if (inventoryMap.getSecond().isEmpty()) inventoryIndexMap.remove(inventoryMap);
                }
            }
        }

        if (toggleTimer > 0) toggleTimer--;

        data.putBoolean("canShare", canShare);
        data.putBoolean("hover", hoverMode);
        data.putByte("toggleTimer", toggleTimer);

        timer++;
        if (timer == Long.MAX_VALUE)
            timer = 0;
    }

    @Override
    public void addInfo(ItemStack itemStack, List<Component> lines) {
        CompoundTag data = itemStack.getOrCreateTag();
        Component state;
        if (data.contains("canShare")) {
            state = data.getBoolean("canShare") ? Component.translatable("metaarmor.hud.status.enabled") :
                    Component.translatable("metaarmor.hud.status.disabled");
        } else {
            state = Component.translatable("metaarmor.hud.status.disabled");
        }
        lines.add(Component.translatable("metaarmor.energy_share.tooltip", state));
        lines.add(Component.translatable("metaarmor.energy_share.tooltip.guide"));

        Component status = Component.translatable("metaarmor.hud.status.disabled");
        if (data.contains("hover")) {
            if (data.getBoolean("hover"))
                status = Component.translatable("metaarmor.hud.status.enabled");
        }
        lines.add(Component.translatable("metaarmor.hud.hover_mode", status));
        super.addInfo(itemStack, lines);
    }

    @Override
    public InteractionResultHolder<ItemStack> onRightClick(Level world, @NotNull Player player, InteractionHand hand) {
        ItemStack armor = player.getItemInHand(hand);

        if (armor.getItem() instanceof ArmorComponentItem && player.isShiftKeyDown()) {
            CompoundTag data = armor.getOrCreateTag();
            boolean canShare = data.contains("canShare") && data.getBoolean("canShare");
            IElectricItem cont = GTCapabilityHelper.getElectricItem(armor);
            if (cont == null) {
                return InteractionResultHolder.fail(armor);
            }

            canShare = !canShare;
            if (!world.isClientSide) {
                if (canShare && cont.getCharge() == 0) {
                    player.sendSystemMessage(Component.translatable("metaarmor.energy_share.error"));
                } else if (canShare) {
                    player.sendSystemMessage(Component.translatable("metaarmor.energy_share.enable"));
                } else {
                    player.sendSystemMessage(Component.translatable("metaarmor.energy_share.disable"));
                }
            }

            canShare = canShare && (cont.getCharge() != 0);
            data.putBoolean("canShare", canShare);
            return InteractionResultHolder.success(armor);
        }

        return super.onRightClick(world, player, hand);
    }

    @OnlyIn(Dist.CLIENT)
    @Override
    public void drawHUD(ItemStack item, GuiGraphics guiGraphics) {
        addCapacityHUD(item, this.HUD);
        IElectricItem cont = GTCapabilityHelper.getElectricItem(item);
        if (cont == null) return;
        if (!cont.canUse(energyPerUse)) return;
        CompoundTag data = item.getTag();
        if (data != null) {
            if (data.contains("canShare")) {
                String status = data.getBoolean("canShare") ? "metaarmor.hud.status.enabled" :
                        "metaarmor.hud.status.disabled";
                this.HUD.newString(Component.translatable("mataarmor.hud.supply_mode", Component.translatable(status)));
            }

            if (data.contains("hover")) {
                String status = data.getBoolean("hover") ? "metaarmor.hud.status.enabled" :
                        "metaarmor.hud.status.disabled";
                this.HUD.newString(Component.translatable("metaarmor.hud.hover_mode", Component.translatable(status)));
            }
        }
        this.HUD.draw(guiGraphics);
        this.HUD.reset();
    }

    @Override
    public ResourceLocation getArmorTexture(ItemStack stack, Entity entity, EquipmentSlot slot, String type) {
        return GTCEu.id("textures/armor/advanced_nano_muscle_suite_1.png");
    }

    @Override
    public boolean canUseEnergy(@NotNull ItemStack stack, int amount) {
        IElectricItem container = getIElectricItem(stack);
        if (container == null)
            return false;
        return container.canUse(amount);
    }

    @Override
    public void drainEnergy(@NotNull ItemStack stack, int amount) {
        IElectricItem container = getIElectricItem(stack);
        if (container == null)
            return;
        container.discharge(amount, tier, true, false, false);
    }

    @Override
    public boolean hasEnergy(@NotNull ItemStack stack) {
        IElectricItem container = getIElectricItem(stack);
        if (container == null)
            return false;
        return container.getCharge() > 0;
    }

    @Nullable
    private static IElectricItem getIElectricItem(@NotNull ItemStack stack) {
        return GTCapabilityHelper.getElectricItem(stack);
    }

    @Override
    public double getSprintEnergyModifier() {
        return 4.0D;
    }

    @Override
    public double getSprintSpeedModifier() {
        return 1.8D;
    }

    @Override
    public double getVerticalHoverSpeed() {
        return 0.4D;
    }

    @Override
    public double getVerticalHoverSlowSpeed() {
        return 0.005D;
    }

    @Override
    public double getVerticalAcceleration() {
        return 0.14D;
    }

    @Override
    public double getVerticalSpeed() {
        return 0.8D;
    }

    @Override
    public double getSidewaysSpeed() {
        return 0.19D;
    }

    @Nullable
    @Override
    public ParticleOptions getParticle() {
        return null;
    }

    @Override
    public float getFallDamageReduction() {
        return 3.5f;
    }

    @Override
    public boolean isPPE() {
        return true;
    }
}
