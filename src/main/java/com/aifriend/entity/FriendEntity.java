package com.aifriend.entity;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nullable;

import com.aifriend.SkinCatalog;
import com.aifriend.ai.AiSettings;
import com.aifriend.entity.goal.FriendBuildGoal;
import com.aifriend.entity.goal.FriendCollectGoal;
import com.aifriend.entity.goal.FriendExploreGoal;
import com.aifriend.entity.goal.FriendFollowGoal;
import com.aifriend.entity.goal.FriendMineGoal;
import com.aifriend.entity.goal.FriendTradeGoal;
import com.aifriend.net.SpeakPayload;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.OpenDoorGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.DiggerItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.item.crafting.SmeltingRecipe;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Бот-игрок. Внешне - игрок, внутри - PathfinderMob с собственным инвентарём.
 * «Мозг» (FriendMind) принимает решения через LLM, а сущность выполняет простые действия.
 */
public class FriendEntity extends PathfinderMob {
    public enum Task {
        FOLLOW, STAY, GUARD, COLLECT, TRADE, BUILD, EXPLORE, SMELT, MINE
    }

    /** Параметры и ход текущей работы (торговля, стройка, разведка, плавка). Не сохраняется между сессиями. */
    public static final class Job {
        /** trade: "buy"/"sell"; build: тип постройки; explore: направление. */
        public String kind = "";
        /** trade/smelt: id предмета. */
        public String item = "";
        /** build: id материала (пусто = выбрать самому). */
        public String material = "";
        public int remaining;
        public int total;
        public int size;
        public int failedScans;
        /** explore: направление и точка старта. */
        public double hx;
        public double hz;
        public double originX;
        public double originZ;
        /** smelt: остаток горения топлива и прогресс текущего предмета (в условных тиках). */
        public int smeltBurn;
        public int smeltProgress;
        public int smelted;
        public final List<String> found = new ArrayList<>();
    }

    private static final int MAX_PLACES = 8;

    private static final EntityDataAccessor<String> DATA_SKIN = SynchedEntityData.defineId(FriendEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Boolean> DATA_FEMALE = SynchedEntityData.defineId(FriendEntity.class, EntityDataSerializers.BOOLEAN);

    private static final int MAX_COLLECT = 64;

    private final SimpleContainer inventory = new SimpleContainer(36);
    private final FriendMind mind = new FriendMind(this);
    private final ArrayDeque<PendingSay> sayQueue = new ArrayDeque<>();
    /** Предметы, которые бот сам выбросил (give) - не подбираем обратно. */
    private final Set<UUID> ignoredItems = new HashSet<>();

    @Nullable
    private UUID ownerUUID;
    private Task task = Task.FOLLOW;
    private String collectTarget = "";
    private int collectRemaining;
    private int collectTotal;
    private boolean lowHealthReported;
    private Job job = new Job();
    /** То, что бот сам нашёл во время разведки (структуры и координаты). Попадает в его «знания». */
    private final List<String> knownPlaces = new ArrayList<>();

    public FriendEntity(EntityType<? extends FriendEntity> type, Level level) {
        super(type, level);
        if (getNavigation() instanceof GroundPathNavigation nav) {
            nav.setCanOpenDoors(true);
        }
        getNavigation().setCanFloat(true);
        setPersistenceRequired();
        setDropChance(EquipmentSlot.MAINHAND, 0.0F);
        setDropChance(EquipmentSlot.OFFHAND, 0.0F);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_SKIN, "");
        builder.define(DATA_FEMALE, false);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 20.0)
                .add(Attributes.MOVEMENT_SPEED, 0.3)
                .add(Attributes.ATTACK_DAMAGE, 2.0)
                .add(Attributes.FOLLOW_RANGE, 32.0);
    }

    // ------------------------------------------------------------------ цели ИИ

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(1, new OpenDoorGoal(this, true));
        this.goalSelector.addGoal(2, new MeleeAttackGoal(this, 1.2, true));
        this.goalSelector.addGoal(3, new FriendCollectGoal(this));
        this.goalSelector.addGoal(3, new FriendTradeGoal(this));
        this.goalSelector.addGoal(3, new FriendBuildGoal(this));
        this.goalSelector.addGoal(3, new FriendExploreGoal(this));
        this.goalSelector.addGoal(3, new FriendMineGoal(this));
        this.goalSelector.addGoal(4, new FriendFollowGoal(this, 1.1, 5.0F, 3.0F));
        this.goalSelector.addGoal(5, new WaterAvoidingRandomStrollGoal(this, 0.8) {
            @Override
            public boolean canUse() {
                return task == Task.FOLLOW && getOwner() == null && super.canUse();
            }
        });
        this.goalSelector.addGoal(6, new LookAtPlayerGoal(this, Player.class, 8.0F));
        this.goalSelector.addGoal(7, new RandomLookAroundGoal(this));

        this.targetSelector.addGoal(1, new HurtByTargetGoal(this));
        this.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, Monster.class, 10, true, false, this::shouldAttack));
    }

    private boolean shouldAttack(LivingEntity target) {
        if (target instanceof Creeper || target instanceof EnderMan) {
            return false;
        }
        double max = switch (task) {
            case STAY -> 4.0;
            case GUARD -> 16.0;
            default -> 10.0;
        };
        return distanceToSqr(target) <= max * max;
    }

    // ------------------------------------------------------------------ тик

    @Override
    public void aiStep() {
        super.aiStep();
        if (level().isClientSide || !isAlive()) {
            return;
        }

        if (getSkin().isEmpty()) {
            // Первое появление (например из яйца): пол, скин и имя берутся из настроек, по умолчанию мужские.
            boolean female = AiSettings.defaultFemale();
            setFemale(female);
            String wanted = SkinCatalog.resolve(AiSettings.get().botSkin);
            setSkin(wanted != null ? wanted : SkinCatalog.defaultFor(female));
        }
        if (!hasCustomName()) {
            setFriendName(AiSettings.pickName(isFemale()));
        }
        if (ownerUUID == null && tickCount % 40 == 0) {
            Player near = level().getNearestPlayer(this, 16.0);
            if (near != null) {
                setOwner(near);
            }
        }

        if (tickCount % 4 == 0) {
            pickUpNearbyItems();
        }
        if (tickCount % 20 == 0) {
            equipArmorFromInventory();
        }
        if (tickCount % 10 == 0) {
            updateHeldItem();
        }
        if (tickCount % 60 == 0) {
            eatIfHurt();
        }
        reportLowHealth();
        tickSayQueue();
        if (task == Task.SMELT) {
            tickSmelt();
        }
        if (tickCount % 20 == 0) {
            mind.tickSocial();
            mind.tickAutonomy();
        }
    }

    private void pickUpNearbyItems() {
        for (ItemEntity ie : level().getEntitiesOfClass(ItemEntity.class, getBoundingBox().inflate(1.5, 0.5, 1.5))) {
            if (ie.isRemoved() || ie.hasPickUpDelay() || ignoredItems.contains(ie.getUUID())) {
                continue;
            }
            ItemStack stack = ie.getItem();
            ItemStack rest = inventory.addItem(stack.copy());
            int taken = stack.getCount() - rest.getCount();
            if (taken > 0) {
                take(ie, taken);
                if (rest.isEmpty()) {
                    ie.discard();
                } else {
                    ie.setItem(rest);
                }
            }
        }
    }

    private void equipArmorFromInventory() {
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack s = inventory.getItem(i);
            if (s.isEmpty()) {
                continue;
            }
            EquipmentSlot slot = getEquipmentSlotForItem(s);
            if (slot.isArmor() && getItemBySlot(slot).isEmpty()) {
                setItemSlot(slot, s.copyWithCount(1));
                setDropChance(slot, 1.0F);
                s.shrink(1);
                inventory.setChanged();
            }
        }
    }

    private void updateHeldItem() {
        if (getTarget() != null) {
            ItemStack best = ItemStack.EMPTY;
            int bestScore = 0;
            for (int i = 0; i < inventory.getContainerSize(); i++) {
                ItemStack s = inventory.getItem(i);
                int score = s.getItem() instanceof SwordItem ? 3 : s.getItem() instanceof AxeItem ? 2 : s.getItem() instanceof DiggerItem ? 1 : 0;
                if (score > bestScore) {
                    best = s;
                    bestScore = score;
                }
            }
            setItemSlot(EquipmentSlot.MAINHAND, best.isEmpty() ? ItemStack.EMPTY : best.copyWithCount(1));
        } else if (task != Task.COLLECT && task != Task.MINE) {
            setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        }
    }

    private void eatIfHurt() {
        if (getHealth() > getMaxHealth() - 4.0F || getTarget() != null) {
            return;
        }
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack s = inventory.getItem(i);
            FoodProperties food = s.isEmpty() ? null : s.get(DataComponents.FOOD);
            if (food != null) {
                heal(Math.max(1, food.nutrition()));
                s.shrink(1);
                inventory.setChanged();
                playSound(SoundEvents.GENERIC_EAT, 1.0F, 1.0F);
                swing(InteractionHand.MAIN_HAND);
                return;
            }
        }
        // Нет еды - медленно восстанавливаемся сами, чтобы бот не умирал от каждой царапины.
        heal(1.0F);
    }

    private void reportLowHealth() {
        if (getHealth() <= 7.0F && !lowHealthReported) {
            lowHealthReported = true;
            mind.onEvent("your health is low: " + Math.round(getHealth()) + "/" + Math.round(getMaxHealth()));
        } else if (getHealth() >= 14.0F) {
            lowHealthReported = false;
        }
    }

    // ------------------------------------------------------------------ владелец и имя

    @Nullable
    public UUID getOwnerUUID() {
        return ownerUUID;
    }

    public void setOwner(@Nullable Player player) {
        this.ownerUUID = player == null ? null : player.getUUID();
    }

    @Nullable
    public Player getOwner() {
        return ownerUUID == null ? null : level().getPlayerByUUID(ownerUUID);
    }

    public void setFriendName(String name) {
        setCustomName(Component.literal(name));
        setCustomNameVisible(true);
    }

    public String getSkin() {
        return entityData.get(DATA_SKIN);
    }

    public void setSkin(String id) {
        entityData.set(DATA_SKIN, id == null ? "" : id);
    }

    public boolean isFemale() {
        return entityData.get(DATA_FEMALE);
    }

    public void setFemale(boolean female) {
        entityData.set(DATA_FEMALE, female);
    }

    public FriendMind getMind() {
        return mind;
    }

    public SimpleContainer getInventory() {
        return inventory;
    }

    // ------------------------------------------------------------------ чат

    private static final class PendingSay {
        final Component message;
        final String text;
        int ticks;

        PendingSay(Component message, String text, int ticks) {
            this.message = message;
            this.text = text;
            this.ticks = ticks;
        }
    }

    /** Ставит реплику в очередь; задержка имитирует набор текста. */
    public void queueSay(String text) {
        Component msg = Component.translatable("chat.type.text", getDisplayName(), Component.literal(text));
        sayQueue.add(new PendingSay(msg, text, Math.min(80, 12 + text.length() * 2)));
    }

    private void tickSayQueue() {
        PendingSay s = sayQueue.peek();
        if (s != null && --s.ticks <= 0) {
            sayQueue.poll();
            if (level() instanceof ServerLevel sl) {
                int radius = Math.max(8, AiSettings.get().hearingRadius);
                for (ServerPlayer p : sl.players()) {
                    if (distanceToSqr(p) <= 64.0 * 64.0) {
                        p.sendSystemMessage(s.message);
                    }
                    // Голос: клиент игрока сам озвучит реплику своим сервисом, если бот достаточно близко.
                    if (distanceToSqr(p) <= (double) radius * radius) {
                        PacketDistributor.sendToPlayer(p, new SpeakPayload(getId(), s.text, radius));
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ задачи

    public Task getTask() {
        return task;
    }

    public String getCollectTarget() {
        return collectTarget;
    }

    public int getCollectRemaining() {
        return collectRemaining;
    }

    public String describeTask() {
        return switch (task) {
            case FOLLOW -> "following the player";
            case STAY -> "standing still and waiting";
            case GUARD -> "guarding the player";
            case COLLECT -> "collecting " + collectTarget + " (" + collectRemaining + " blocks left)";
            case TRADE -> ("sell".equals(job.kind) ? "selling " : "buying ") + job.item + " from a villager (" + job.remaining + " trades left)";
            case BUILD -> "building a " + job.kind + " (" + job.remaining + " blocks left)";
            case EXPLORE -> "scouting around (" + job.remaining + " blocks left to walk)";
            case SMELT -> "smelting " + job.item + " (" + job.remaining + " left)";
            case MINE -> "digging a mine for " + job.item + " (" + job.remaining + " blocks to go)";
        };
    }

    public Job job() {
        return job;
    }

    /** Завершает текущую работу и сообщает «мозгу», чем всё кончилось. */
    public void endJob(boolean success, String message) {
        task = Task.FOLLOW;
        getNavigation().stop();
        setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        mind.onImportantEvent(message);
    }

    public boolean knowsPlace(String id) {
        for (String p : knownPlaces) {
            if (p.startsWith(id + " at ")) {
                return true;
            }
        }
        return false;
    }

    public void addKnownPlace(String description) {
        knownPlaces.add(description);
        while (knownPlaces.size() > MAX_PLACES) {
            knownPlaces.remove(0);
        }
    }

    public List<String> getKnownPlaces() {
        return knownPlaces;
    }

    // ------------------------------------------------------------------ инвентарь: подсчёт и списание

    public int countInInventory(ItemStack proto) {
        int n = 0;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack s = inventory.getItem(i);
            if (ItemStack.isSameItem(s, proto)) {
                n += s.getCount();
            }
        }
        return n;
    }

    /** Списывает предметы (proto.getCount() штук того же вида). false, если не хватило (тогда ничего не списано). */
    public boolean removeFromInventory(ItemStack proto) {
        if (countInInventory(proto) < proto.getCount()) {
            return false;
        }
        int left = proto.getCount();
        for (int i = 0; i < inventory.getContainerSize() && left > 0; i++) {
            ItemStack s = inventory.getItem(i);
            if (ItemStack.isSameItem(s, proto)) {
                int n = Math.min(left, s.getCount());
                inventory.removeItem(i, n);
                left -= n;
            }
        }
        return true;
    }

    /**
     * Выполняет действие, выбранное «мозгом». Вызывается на серверном потоке.
     * Возвращает текст события для мозга (результат), либо null.
     */
    @Nullable
    public String applyAction(String action, String target, int count) {
        String t = normalizeId(target);
        switch (action) {
            case "follow" -> {
                task = Task.FOLLOW;
                return null;
            }
            case "come" -> {
                task = Task.FOLLOW;
                Player owner = getOwner();
                if (owner != null) {
                    getNavigation().moveTo(owner, 1.2);
                }
                return null;
            }
            case "stay" -> {
                task = Task.STAY;
                getNavigation().stop();
                return null;
            }
            case "guard" -> {
                task = Task.GUARD;
                return null;
            }
            case "collect" -> {
                if (t.isEmpty()) {
                    return "could not start collecting: no target given";
                }
                collectTarget = t;
                collectRemaining = Math.max(1, Math.min(MAX_COLLECT, count <= 0 ? 1 : count));
                collectTotal = collectRemaining;
                task = Task.COLLECT;
                return null;
            }
            case "craft" -> {
                return craft(t, Math.max(1, Math.min(64, count <= 0 ? 1 : count)));
            }
            case "give" -> {
                return give(t, Math.max(1, Math.min(64, count <= 0 ? 1 : count)));
            }
            case "trade", "sell" -> {
                if (t.isEmpty()) {
                    return "could not trade: no item given";
                }
                job = new Job();
                job.kind = action.equals("sell") ? "sell" : "buy";
                job.item = t;
                job.total = job.remaining = Math.max(1, Math.min(16, count <= 0 ? 1 : count));
                task = Task.TRADE;
                return null;
            }
            case "build" -> {
                String[] parts = (target == null ? "" : target).trim().toLowerCase(Locale.ROOT).split(":", 2);
                job = new Job();
                job.kind = parts[0].trim().replace(' ', '_');
                job.material = parts.length > 1 ? normalizeId(parts[1]) : "";
                job.size = count;
                task = Task.BUILD;
                return null;
            }
            case "explore" -> {
                job = new Job();
                job.kind = t;
                double hx;
                double hz;
                switch (t) {
                    case "north" -> { hx = 0; hz = -1; }
                    case "south" -> { hx = 0; hz = 1; }
                    case "east" -> { hx = 1; hz = 0; }
                    case "west" -> { hx = -1; hz = 0; }
                    default -> {
                        double a = random.nextDouble() * Math.PI * 2.0;
                        hx = Math.cos(a);
                        hz = Math.sin(a);
                    }
                }
                job.hx = hx;
                job.hz = hz;
                Player owner = getOwner();
                job.originX = owner != null ? owner.getX() : getX();
                job.originZ = owner != null ? owner.getZ() : getZ();
                job.total = job.remaining = Math.max(20, Math.min(300, count <= 0 ? 60 : count));
                task = Task.EXPLORE;
                return null;
            }
            case "mine" -> {
                return startMine(t, count);
            }
            case "smelt" -> {
                if (t.isEmpty()) {
                    return "could not smelt: no item given";
                }
                job = new Job();
                job.item = t;
                job.total = job.remaining = Math.max(1, Math.min(64, count <= 0 ? 8 : count));
                task = Task.SMELT;
                getNavigation().stop();
                return null;
            }
            default -> {
                return null;
            }
        }
    }

    // ------------------------------------------------------------------ шахта

    /** Ключ руды ("diamond", "iron"...) из того, что написал мозг ("diamonds", "diamond_ore", "deepslate_iron_ore"), либо "". */
    public static String oreKey(String t) {
        for (String k : new String[] { "diamond", "redstone", "lapis", "copper", "coal", "iron", "gold", "emerald" }) {
            if (t.contains(k)) {
                return k;
            }
        }
        return "";
    }

    /** Высота (Y), на которой руда в 1.21 встречается чаще всего. */
    public static int mineLevel(String key) {
        return switch (key) {
            case "diamond" -> -54;
            case "redstone" -> -54;
            case "gold" -> -16;
            case "lapis" -> 0;
            case "iron" -> 16;
            case "copper" -> 40;
            default -> 45; // уголь
        };
    }

    @Nullable
    private String startMine(String t, int count) {
        String key = oreKey(t);
        if (key.isEmpty()) {
            return "could not mine '" + t + "': you only know how to dig for coal, iron, copper, gold, redstone, lapis and diamond";
        }
        if (key.equals("emerald")) {
            return "could not mine emeralds: they only generate in mountain biomes, digging a mine will not find them";
        }
        if (pickTool(Blocks.STONE.defaultBlockState()).isEmpty()) {
            return "could not start a mine: you have no pickaxe (craft a wooden pickaxe first, stone and ores need one)";
        }
        net.minecraft.world.level.block.Block oreBlock = switch (key) {
            case "diamond" -> Blocks.DIAMOND_ORE;
            case "redstone" -> Blocks.REDSTONE_ORE;
            case "gold" -> Blocks.GOLD_ORE;
            case "lapis" -> Blocks.LAPIS_ORE;
            case "iron" -> Blocks.IRON_ORE;
            case "copper" -> Blocks.COPPER_ORE;
            default -> Blocks.COAL_ORE;
        };
        if (pickTool(oreBlock.defaultBlockState()).isEmpty()) {
            String need = switch (key) {
                case "diamond", "redstone", "gold" -> "an iron pickaxe";
                default -> "a stone pickaxe";
            };
            return "could not mine " + key + ": it only drops with " + need + " or better, and you don't have one";
        }
        job = new Job();
        job.kind = key;
        job.item = key;
        job.total = job.remaining = Math.max(1, Math.min(32, count <= 0 ? 3 : count));
        job.size = mineLevel(key);
        Player owner = getOwner();
        float yaw = owner != null ? owner.getYRot() : getYRot();
        net.minecraft.core.Direction dir = net.minecraft.core.Direction.fromYRot(yaw);
        job.hx = dir.getStepX();
        job.hz = dir.getStepZ();
        task = Task.MINE;
        return null;
    }

    /** Вызывается целью MINE после каждой добытой руды. */
    public void onOreMined() {
        job.remaining--;
        job.smelted++; // сколько уже добыто
    }

    // ------------------------------------------------------------------ плавка

    /** Вызывается каждый тик, пока задача SMELT. Печка работает вдвое быстрее обычной. */
    private void tickSmelt() {
        Level level = level();
        getNavigation().stop();
        if (job.remaining <= 0) {
            endJob(true, "finished smelting " + job.item + " (" + job.smelted + " done)");
            return;
        }
        if (!blockNearby(Blocks.FURNACE, 4) && !placeBlockFromInventory(net.minecraft.world.item.Items.FURNACE, Blocks.FURNACE)) {
            endJob(false, "cannot smelt: no furnace nearby and none in the inventory (a furnace needs 8 cobblestone)");
            return;
        }

        int slot = -1;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack s = inventory.getItem(i);
            if (!s.isEmpty() && BuiltInRegistries.ITEM.getKey(s.getItem()).getPath().equals(job.item)) {
                slot = i;
                break;
            }
        }
        if (slot < 0) {
            endJob(job.smelted > 0, job.smelted > 0
                    ? "smelted " + job.smelted + " " + job.item + ", no more left in the inventory"
                    : "cannot smelt '" + job.item + "': you don't have it");
            return;
        }
        ItemStack in = inventory.getItem(slot);
        var recipe = level.getRecipeManager().getRecipeFor(RecipeType.SMELTING, new SingleRecipeInput(in), level);
        if (recipe.isEmpty()) {
            endJob(false, "cannot smelt '" + job.item + "': it can't be smelted");
            return;
        }

        if (job.smeltBurn <= 0) {
            int fuelSlot = findFuel(slot);
            if (fuelSlot < 0) {
                endJob(job.smelted > 0, "ran out of fuel (coal, charcoal, planks or logs) after smelting " + job.smelted);
                return;
            }
            ItemStack fuel = inventory.getItem(fuelSlot);
            job.smeltBurn = fuel.getBurnTime(RecipeType.SMELTING);
            fuel.shrink(1);
            inventory.setChanged();
        }

        job.smeltBurn -= 2;
        job.smeltProgress += 2;
        if (tickCount % 20 == 0) {
            swing(InteractionHand.MAIN_HAND);
        }
        SmeltingRecipe sr = recipe.get().value();
        if (job.smeltProgress >= sr.getCookingTime()) {
            job.smeltProgress = 0;
            ItemStack out = sr.getResultItem(level.registryAccess()).copy();
            in.shrink(1);
            ItemStack rest = inventory.addItem(out);
            if (!rest.isEmpty()) {
                spawnAtLocation(rest);
            }
            inventory.setChanged();
            job.smelted++;
            job.remaining--;
        }
    }

    private int findFuel(int exceptSlot) {
        int best = -1;
        int bestScore = -1;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack s = inventory.getItem(i);
            if (i == exceptSlot || s.isEmpty() || s.getBurnTime(RecipeType.SMELTING) <= 0) {
                continue;
            }
            String path = BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
            if (path.equals(job.item)) {
                continue;
            }
            int score;
            if (path.equals("charcoal") || path.equals("coal")) {
                score = 4;
            } else if (path.equals("stick")) {
                score = 3;
            } else if (path.endsWith("_planks")) {
                score = 2;
            } else if (path.endsWith("_log") || path.endsWith("_wood")) {
                score = 1;
            } else {
                continue; // инструменты, мебель и прочее горючее не жжём
            }
            if (score > bestScore) {
                bestScore = score;
                best = i;
            }
        }
        return best;
    }

    private boolean blockNearby(net.minecraft.world.level.block.Block block, int radius) {
        BlockPos c = blockPosition();
        for (BlockPos p : BlockPos.betweenClosed(c.offset(-radius, -2, -radius), c.offset(radius, 2, radius))) {
            if (level().getBlockState(p).is(block)) {
                return true;
            }
        }
        return false;
    }

    /** Ставит блок из инвентаря на свободное место рядом (верстак, печь). */
    private boolean placeBlockFromInventory(Item item, net.minecraft.world.level.block.Block block) {
        int slot = -1;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            if (inventory.getItem(i).is(item)) {
                slot = i;
                break;
            }
        }
        if (slot < 0) {
            return false;
        }
        BlockPos c = blockPosition();
        for (BlockPos p : BlockPos.betweenClosed(c.offset(-2, 0, -2), c.offset(2, 0, 2))) {
            if (p.equals(c) || p.equals(c.above())) {
                continue;
            }
            if (level().getBlockState(p).isAir() && level().getBlockState(p.below()).isSolid()) {
                level().setBlock(p.immutable(), block.defaultBlockState(), 3);
                inventory.removeItem(slot, 1);
                swing(InteractionHand.MAIN_HAND);
                return true;
            }
        }
        return false;
    }

    /** Вызывается целью COLLECT после каждого сломанного блока. */
    public void onBlockCollected() {
        collectRemaining--;
        if (collectRemaining <= 0) {
            finishCollect(true);
        }
    }

    public void finishCollect(boolean success) {
        String target = collectTarget;
        int done = collectTotal - Math.max(0, collectRemaining);
        collectRemaining = 0;
        task = Task.FOLLOW;
        setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        if (success) {
            mind.onImportantEvent("finished collecting " + target + " (" + done + " blocks)");
        } else {
            mind.onImportantEvent("gave up collecting " + target + ": got " + done + " of " + collectTotal
                    + ", nothing more reachable nearby or no suitable tool");
        }
    }

    // ------------------------------------------------------------------ добыча: инструменты

    public boolean isCollectTarget(BlockState state) {
        String path = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
        if (blockMatches(path, collectTarget)) {
            return true;
        }
        // Попросили "oak_log", а вокруг саванна: игроку подойдёт любое бревно.
        return collectTarget.endsWith("_log") && path.endsWith("_log") && !path.startsWith("stripped_");
    }

    static boolean blockMatches(String path, String t) {
        if (path.equals(t)) {
            return true;
        }
        return switch (t) {
            case "log", "logs", "wood", "tree" -> path.endsWith("_log") && !path.startsWith("stripped_");
            case "ore" -> path.endsWith("_ore");
            case "coal", "iron", "gold", "diamond", "copper", "redstone", "lapis", "emerald" ->
                    path.equals(t + "_ore") || path.endsWith("_" + t + "_ore");
            case "cobblestone" -> path.equals("stone");
            case "leaves" -> path.endsWith("_leaves");
            default -> false;
        };
    }

    /** Лучший инструмент из инвентаря для блока; EMPTY = рука. */
    public ItemStack pickTool(BlockState state) {
        ItemStack best = ItemStack.EMPTY;
        float bestSpeed = state.requiresCorrectToolForDrops() ? 0.0F : 1.0F;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack s = inventory.getItem(i);
            if (s.isEmpty()) {
                continue;
            }
            boolean ok = !state.requiresCorrectToolForDrops() || s.isCorrectToolForDrops(state);
            float speed = s.getDestroySpeed(state);
            if (ok && speed > bestSpeed) {
                best = s;
                bestSpeed = speed;
            }
        }
        return best;
    }

    public boolean canHarvest(BlockState state) {
        if (state.getDestroySpeed(level(), BlockPos.ZERO) < 0) {
            return false;
        }
        return !state.requiresCorrectToolForDrops() || !pickTool(state).isEmpty();
    }

    public void equipToolFor(BlockState state) {
        ItemStack tool = pickTool(state);
        setItemSlot(EquipmentSlot.MAINHAND, tool.isEmpty() ? ItemStack.EMPTY : tool.copyWithCount(1));
    }

    // ------------------------------------------------------------------ крафт

    @Nullable
    private String craft(String itemId, int wanted) {
        // Доски: подойдёт любой вид, какой получается из брёвен в инвентаре (в саванне бот носит акацию, а просит oak_planks).
        if (itemId.equals("planks") || itemId.endsWith("_planks")) {
            String requestedResult = null;
            for (Item it : BuiltInRegistries.ITEM) {
                String p = BuiltInRegistries.ITEM.getKey(it).getPath();
                if (!p.endsWith("_planks")) {
                    continue;
                }
                String r = craftOne(p, wanted);
                if (r != null && r.startsWith("crafted")) {
                    return r;
                }
                if (p.equals(itemId)) {
                    requestedResult = r;
                }
            }
            return requestedResult != null ? requestedResult : "could not craft planks: you have no logs";
        }
        return craftOne(itemId, wanted);
    }

    @Nullable
    private String craftOne(String itemId, int wanted) {
        ResourceLocation rl = ResourceLocation.tryParse(itemId);
        if (rl == null) {
            return "could not craft: bad item id '" + itemId + "'";
        }
        Item item = BuiltInRegistries.ITEM.getOptional(rl).orElse(null);
        if (item == null) {
            return "could not craft '" + itemId + "': no such item";
        }
        boolean needTableMissing = false;
        for (RecipeHolder<CraftingRecipe> holder : level().getRecipeManager().getAllRecipesFor(RecipeType.CRAFTING)) {
            CraftingRecipe recipe = holder.value();
            if (recipe.isSpecial()) {
                continue;
            }
            ItemStack out = recipe.getResultItem(level().registryAccess());
            if (out.getItem() != item || out.isEmpty()) {
                continue;
            }
            List<Ingredient> ings = recipe.getIngredients().stream().filter(i -> !i.isEmpty()).toList();
            if (ings.isEmpty() || !hasIngredients(ings)) {
                continue;
            }
            if (needsTable(recipe) && !tableNearby() && !placeTableFromInventory()) {
                needTableMissing = true;
                continue;
            }
            int crafts = (wanted + out.getCount() - 1) / out.getCount();
            int made = 0;
            for (int i = 0; i < crafts; i++) {
                if (!consume(ings)) {
                    break;
                }
                ItemStack result = out.copy();
                ItemStack rest = inventory.addItem(result);
                if (!rest.isEmpty()) {
                    spawnAtLocation(rest);
                }
                made += out.getCount();
            }
            if (made > 0) {
                swing(InteractionHand.MAIN_HAND);
                playSound(SoundEvents.UI_STONECUTTER_TAKE_RESULT, 0.6F, 1.0F);
                return "crafted " + made + " " + itemId;
            }
        }
        return needTableMissing
                ? "could not craft " + itemId + ": needs a crafting table nearby (and you have none to place)"
                : "could not craft " + itemId + ": missing materials or unknown recipe";
    }

    private static boolean needsTable(CraftingRecipe recipe) {
        if (recipe instanceof ShapedRecipe shaped) {
            return shaped.getWidth() > 2 || shaped.getHeight() > 2;
        }
        return recipe.getIngredients().stream().filter(i -> !i.isEmpty()).count() > 4;
    }

    private boolean hasIngredients(List<Ingredient> ings) {
        return allocate(ings) != null;
    }

    private boolean consume(List<Ingredient> ings) {
        int[] used = allocate(ings);
        if (used == null) {
            return false;
        }
        for (int slot = 0; slot < used.length; slot++) {
            if (used[slot] > 0) {
                inventory.removeItem(slot, used[slot]);
            }
        }
        return true;
    }

    /** Сколько предметов из каждого слота потребуется, либо null, если материалов не хватает. */
    @Nullable
    private int[] allocate(List<Ingredient> ings) {
        int[] used = new int[inventory.getContainerSize()];
        for (Ingredient ing : ings) {
            boolean found = false;
            for (int slot = 0; slot < used.length; slot++) {
                ItemStack s = inventory.getItem(slot);
                if (!s.isEmpty() && s.getCount() - used[slot] > 0 && ing.test(s)) {
                    used[slot]++;
                    found = true;
                    break;
                }
            }
            if (!found) {
                return null;
            }
        }
        return used;
    }

    private boolean tableNearby() {
        BlockPos c = blockPosition();
        for (BlockPos p : BlockPos.betweenClosed(c.offset(-4, -2, -4), c.offset(4, 2, 4))) {
            if (level().getBlockState(p).is(Blocks.CRAFTING_TABLE)) {
                return true;
            }
        }
        return false;
    }

    private boolean placeTableFromInventory() {
        int slot = -1;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            if (inventory.getItem(i).is(net.minecraft.world.item.Items.CRAFTING_TABLE)) {
                slot = i;
                break;
            }
        }
        if (slot < 0) {
            return false;
        }
        BlockPos c = blockPosition();
        for (BlockPos p : BlockPos.betweenClosed(c.offset(-2, 0, -2), c.offset(2, 0, 2))) {
            if (p.equals(c) || p.equals(c.above())) {
                continue;
            }
            if (level().getBlockState(p).isAir() && level().getBlockState(p.below()).isSolid()) {
                level().setBlock(p.immutable(), Blocks.CRAFTING_TABLE.defaultBlockState(), 3);
                inventory.removeItem(slot, 1);
                swing(InteractionHand.MAIN_HAND);
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ передача игроку

    @Nullable
    private String give(String itemId, int wanted) {
        Player owner = getOwner();
        if (owner == null) {
            return "could not give '" + itemId + "': the player is not around";
        }
        int remaining = wanted;
        int given = 0;
        for (int i = 0; i < inventory.getContainerSize() && remaining > 0; i++) {
            ItemStack s = inventory.getItem(i);
            if (s.isEmpty() || !BuiltInRegistries.ITEM.getKey(s.getItem()).getPath().equals(stripNamespace(itemId))) {
                continue;
            }
            int n = Math.min(remaining, s.getCount());
            ItemStack out = inventory.removeItem(i, n);
            ItemEntity dropped = spawnAtLocation(out);
            if (dropped != null) {
                ignoredItems.add(dropped.getUUID());
            }
            remaining -= n;
            given += n;
        }
        if (ignoredItems.size() > 200) {
            ignoredItems.clear();
        }
        if (given > 0) {
            swing(InteractionHand.MAIN_HAND);
            return "dropped " + given + " " + itemId + " for the player";
        }
        return "could not give '" + itemId + "': you don't have it";
    }

    // ------------------------------------------------------------------ взаимодействие

    @Override
    protected InteractionResult mobInteract(Player player, InteractionHand hand) {
        ItemStack held = player.getItemInHand(hand);
        if (ownerUUID == null) {
            setOwner(player);
        }
        if (!held.isEmpty()) {
            ItemStack rest = inventory.addItem(held.copy());
            int moved = held.getCount() - rest.getCount();
            if (moved > 0) {
                if (!level().isClientSide) {
                    String id = BuiltInRegistries.ITEM.getKey(held.getItem()).getPath();
                    if (!player.getAbilities().instabuild) {
                        held.shrink(moved);
                    }
                    playSound(SoundEvents.ITEM_PICKUP, 0.5F, 1.0F);
                    mind.onEvent(player.getGameProfile().getName() + " handed you " + moved + " " + id);
                }
                return InteractionResult.sidedSuccess(level().isClientSide);
            }
        }
        return super.mobInteract(player, hand);
    }

    // ------------------------------------------------------------------ звуки, смерть, сохранение

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return SoundEvents.PLAYER_HURT;
    }

    @Override
    protected SoundEvent getDeathSound() {
        return SoundEvents.PLAYER_DEATH;
    }

    @Override
    public boolean removeWhenFarAway(double distanceToClosestPlayer) {
        return false;
    }

    @Override
    public void die(DamageSource source) {
        if (!level().isClientSide) {
            Containers.dropContents(level(), blockPosition(), inventory);
            inventory.clearContent();
        }
        super.die(source);
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        if (ownerUUID != null) {
            tag.putUUID("FriendOwner", ownerUUID);
        }
        // Долгие работы (торговля, стройка, разведка, плавка) после перезагрузки не продолжаем.
        boolean persistent = task == Task.FOLLOW || task == Task.STAY || task == Task.GUARD || task == Task.COLLECT;
        tag.putString("FriendSkin", getSkin());
        tag.putBoolean("FriendFemale", isFemale());
        tag.putString("FriendTask", (persistent ? task : Task.FOLLOW).name());
        net.minecraft.nbt.ListTag places = new net.minecraft.nbt.ListTag();
        for (String p : knownPlaces) {
            places.add(net.minecraft.nbt.StringTag.valueOf(p));
        }
        tag.put("FriendPlaces", places);
        tag.putString("FriendCollectTarget", collectTarget);
        tag.putInt("FriendCollectRemaining", collectRemaining);
        tag.putInt("FriendCollectTotal", collectTotal);
        tag.put("FriendInventory", inventory.createTag(registryAccess()));
        mind.save(tag);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.hasUUID("FriendOwner")) {
            ownerUUID = tag.getUUID("FriendOwner");
        }
        try {
            task = Task.valueOf(tag.getString("FriendTask"));
        } catch (IllegalArgumentException e) {
            task = Task.FOLLOW;
        }
        knownPlaces.clear();
        if (tag.contains("FriendPlaces", Tag.TAG_LIST)) {
            net.minecraft.nbt.ListTag places = tag.getList("FriendPlaces", Tag.TAG_STRING);
            for (int i = 0; i < places.size(); i++) {
                knownPlaces.add(places.getString(i));
            }
        }
        setSkin(tag.getString("FriendSkin"));
        setFemale(tag.getBoolean("FriendFemale"));
        collectTarget = tag.getString("FriendCollectTarget");
        collectRemaining = tag.getInt("FriendCollectRemaining");
        collectTotal = tag.getInt("FriendCollectTotal");
        if (tag.contains("FriendInventory", Tag.TAG_LIST)) {
            inventory.fromTag(tag.getList("FriendInventory", Tag.TAG_COMPOUND), registryAccess());
        }
        mind.load(tag);
    }

    // ------------------------------------------------------------------ утилиты

    public static String normalizeId(String raw) {
        if (raw == null) {
            return "";
        }
        return stripNamespace(raw.trim().toLowerCase(Locale.ROOT).replace(' ', '_'));
    }

    private static String stripNamespace(String id) {
        int i = id.indexOf(':');
        return i >= 0 ? id.substring(i + 1) : id;
    }
}
