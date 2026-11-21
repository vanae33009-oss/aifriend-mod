package com.aifriend.entity.goal;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;

import javax.annotation.Nullable;

import com.aifriend.entity.FriendEntity;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.MerchantOffer;

/**
 * Торговля с жителями. Бот сам находит ближайшего жителя (или странствующего торговца), у которого есть нужная сделка,
 * подходит и обменивает вещи из своего инвентаря. kind = "buy": бот получает item; kind = "sell": бот отдаёт item.
 * Количество (job.remaining) - число сделок.
 */
public class FriendTradeGoal extends Goal {
    private final FriendEntity friend;
    @Nullable
    private AbstractVillager target;
    private int scanCooldown;
    private int walkTicks;

    public FriendTradeGoal(FriendEntity friend) {
        this.friend = friend;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (friend.getTask() != FriendEntity.Task.TRADE || friend.job().remaining <= 0) {
            return false;
        }
        if (--scanCooldown > 0) {
            return false;
        }
        scanCooldown = 10;

        FriendEntity.Job job = friend.job();
        List<AbstractVillager> villagers = friend.level().getEntitiesOfClass(AbstractVillager.class,
                friend.getBoundingBox().inflate(24.0), v -> v.isAlive() && !v.isBaby());
        villagers.sort(Comparator.comparingDouble(friend::distanceToSqr));

        String unaffordable = null;
        for (AbstractVillager v : villagers) {
            MerchantOffer affordable = findOffer(v, true);
            if (affordable != null) {
                target = v;
                walkTicks = 0;
                return true;
            }
            if (unaffordable == null) {
                MerchantOffer any = findOffer(v, false);
                if (any != null) {
                    unaffordable = describe(any);
                }
            }
        }
        if (unaffordable != null) {
            fail("a villager has that trade (" + unaffordable + "), but you don't have enough of the payment");
        } else if (++job.failedScans >= 3) {
            fail("no villager nearby has a trade for '" + job.item + "'");
        }
        return false;
    }

    @Override
    public boolean canContinueToUse() {
        return friend.getTask() == FriendEntity.Task.TRADE && target != null && target.isAlive();
    }

    @Override
    public void stop() {
        friend.getNavigation().stop();
        target = null;
    }

    @Override
    public void tick() {
        if (target == null) {
            return;
        }
        friend.getLookControl().setLookAt(target, 30.0F, 30.0F);
        if (friend.distanceToSqr(target) > 2.5 * 2.5) {
            if (walkTicks++ % 10 == 0) {
                friend.getNavigation().moveTo(target, 1.0);
            }
            if (walkTicks > 400) {
                fail("could not reach the villager");
            }
            return;
        }
        friend.getNavigation().stop();
        trade(target);
    }

    // ------------------------------------------------------------------

    private void trade(AbstractVillager villager) {
        FriendEntity.Job job = friend.job();
        int made = 0;
        StringBuilder last = new StringBuilder();
        while (job.remaining > 0) {
            MerchantOffer offer = findOffer(villager, true);
            if (offer == null) {
                break;
            }
            ItemStack costA = offer.getCostA();
            ItemStack costB = offer.getCostB();
            ItemStack result = offer.getResult().copy();
            if (!friend.removeFromInventory(costA) || (!costB.isEmpty() && !friend.removeFromInventory(costB))) {
                break;
            }
            ItemStack rest = friend.getInventory().addItem(result);
            if (!rest.isEmpty()) {
                friend.spawnAtLocation(rest);
            }
            villager.notifyTrade(offer);
            last.setLength(0);
            last.append(describe(offer));
            made++;
            job.remaining--;
        }
        if (made > 0) {
            friend.swing(InteractionHand.MAIN_HAND);
            villager.playSound(SoundEvents.VILLAGER_YES, 1.0F, 1.0F);
        }
        target = null;
        if (job.remaining <= 0) {
            friend.endJob(true, "finished trading: " + made + " trade(s) done (" + last + ")");
        } else if (made > 0) {
            friend.endJob(true, "made " + made + " trade(s) (" + last + "), then the villager ran out of stock or you ran out of payment");
        } else {
            fail("the villager has nothing more to trade for that");
        }
    }

    @Nullable
    private MerchantOffer findOffer(AbstractVillager villager, boolean mustAfford) {
        FriendEntity.Job job = friend.job();
        boolean buying = !"sell".equals(job.kind);
        for (MerchantOffer offer : villager.getOffers()) {
            if (offer.isOutOfStock()) {
                continue;
            }
            boolean match;
            if (buying) {
                match = id(offer.getResult()).equals(job.item);
            } else {
                match = id(offer.getCostA()).equals(job.item) || (!offer.getCostB().isEmpty() && id(offer.getCostB()).equals(job.item));
            }
            if (!match) {
                continue;
            }
            if (mustAfford && !canAfford(offer)) {
                continue;
            }
            return offer;
        }
        return null;
    }

    private boolean canAfford(MerchantOffer offer) {
        ItemStack a = offer.getCostA();
        ItemStack b = offer.getCostB();
        if (friend.countInInventory(a) < a.getCount()) {
            return false;
        }
        return b.isEmpty() || friend.countInInventory(b) >= b.getCount()
                + (ItemStack.isSameItem(a, b) ? a.getCount() : 0);
    }

    private static String describe(MerchantOffer offer) {
        ItemStack a = offer.getCostA();
        ItemStack b = offer.getCostB();
        ItemStack r = offer.getResult();
        String pay = a.getCount() + " " + id(a) + (b.isEmpty() ? "" : " + " + b.getCount() + " " + id(b));
        return pay + " -> " + r.getCount() + " " + id(r);
    }

    private static String id(ItemStack s) {
        return BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
    }

    private void fail(String reason) {
        target = null;
        friend.endJob(false, "could not trade: " + reason);
    }
}
