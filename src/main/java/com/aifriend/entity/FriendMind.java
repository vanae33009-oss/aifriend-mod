package com.aifriend.entity;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletionException;

import javax.annotation.Nullable;

import com.aifriend.AiFriendMod;
import com.aifriend.ai.AiClient;
import com.aifriend.ai.AiSettings;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

/**
 * «Мозг» бота: хранит короткую память разговора, формирует запрос к LLM и разбирает ответ
 * (реплика в чат + действие). Все методы вызываются на серверном потоке; HTTP идёт в фоне.
 */
public final class FriendMind {
    private static final int MAX_HISTORY = 16;
    private static final long EVENT_COOLDOWN_MS = 8_000;
    private static final long ERROR_COOLDOWN_MS = 30_000;

    private final FriendEntity friend;
    private final Deque<AiClient.Message> history = new ArrayDeque<>();
    private final List<String> pendingLines = new ArrayList<>();
    private boolean busy;
    /** Короткая «заметка себе» о том, чем бот занят в целом (его собственный план). */
    private String plan = "";
    private long lastActivityMs = System.currentTimeMillis();
    private long lastEventMs;
    private long lastErrorMs;
    @Nullable
    private ServerPlayer lastSpeaker;
    private final FriendSocial social = new FriendSocial();
    /** Настроение одним-двумя словами (пишет сам бот). */
    private String mood = "";
    /** Что бот запомнил об игроке (его собственные заметки: имя, вкусы, как человек любит играть). */
    private final List<String> notes = new ArrayList<>();
    private static final int MAX_NOTES = 14;
    /** Шаги, которые ИИ заказал цепочкой: выполняются по очереди без новых запросов. */
    private final Deque<Step> queue = new ArrayDeque<>();

    record Step(String action, String target, int count) {
    }

    FriendMind(FriendEntity friend) {
        this.friend = friend;
    }

    // ------------------------------------------------------------------ входы

    /** Игрок написал в чат (или сказал голосом - см. FriendChat). */
    public void onPlayerMessage(ServerPlayer speaker, String text) {
        lastSpeaker = speaker;
        lastActivityMs = System.currentTimeMillis();
        social.onPlayerSpoke();
        pendingLines.add(speaker.getGameProfile().getName() + ": " + text);
        pump();
    }

    /** Что-то произошло с ботом (закончил работу, ранен, получил предмет...). */
    public void onEvent(String description) {
        long now = System.currentTimeMillis();
        if (now - lastEventMs < EVENT_COOLDOWN_MS) {
            return; // не засыпаем API мелкими событиями
        }
        onImportantEvent(description);
    }

    /** Событие без ограничения частоты (результат работы, которого игрок ждёт). */
    public void onImportantEvent(String description) {
        if (!AiSettings.isConfigured()) {
            return;
        }
        lastEventMs = System.currentTimeMillis();
        lastActivityMs = lastEventMs;
        showDebug("[event] " + description);
        pendingLines.add("[event] " + description);
        pump();
    }

    /** Серое служебное сообщение игроку: что бот решил и чем кончилось (настройка «Отладка», по умолчанию выключена). */
    private void showDebug(String text) {
        AiFriendMod.LOGGER.info("Friend '{}' {}", friend.getName().getString(), text);
        if (!AiSettings.get().debugMessages) {
            return;
        }
        Player p = lastSpeaker != null ? lastSpeaker : friend.getOwner();
        if (p instanceof ServerPlayer sp) {
            sp.sendSystemMessage(Component.literal(text).withStyle(ChatFormatting.DARK_GRAY));
        }
    }

    public String getPlan() {
        return plan;
    }

    public String getMood() {
        return mood;
    }

    public List<String> getNotes() {
        return notes;
    }

    /**
     * Долгая работа закончилась. Если успешно и в очереди есть следующие шаги, продолжаем без запроса к ИИ,
     * иначе очередь сбрасывается и ИИ узнаёт, чем всё кончилось.
     */
    public void onJobDone(boolean success, String message) {
        if (success && !queue.isEmpty()) {
            AiFriendMod.LOGGER.info("Friend '{}' [done] {}", friend.getName().getString(), message);
            String result = runQueue();
            if (result != null) {
                onImportantEvent(result);
            }
            return;
        }
        queue.clear();
        onImportantEvent(message);
    }

    /**
     * Выполняет шаги из очереди, пока они мгновенные (крафт, отдать). Как только запущена долгая работа,
     * остальное ждёт её конца. Возвращает текст провала, если шаг не удался (тогда очередь очищена).
     */
    @Nullable
    private String runQueue() {
        while (!queue.isEmpty()) {
            Step st = queue.pollFirst();
            showDebug("[step] " + st.action() + (st.target().isEmpty() ? "" : " " + st.target()) + (st.count() > 0 ? " x" + st.count() : ""));
            String result = friend.applyAction(st.action(), st.target(), st.count());
            if (result != null) {
                showDebug("[result] " + result);
                if (result.startsWith("could not") || result.startsWith("cannot")) {
                    queue.clear();
                    return result;
                }
            }
            if (friend.getTask() != FriendEntity.Task.FOLLOW && friend.getTask() != FriendEntity.Task.STAY
                    && friend.getTask() != FriendEntity.Task.GUARD) {
                return null; // запущена долгая работа: продолжим, когда она закончится
            }
        }
        return null;
    }

    boolean isBusy() {
        return busy || !pendingLines.isEmpty();
    }

    /** Раз в секунду: знакомство, приветствие, инициатива в разговоре. */
    public void tickSocial() {
        social.tick(friend, this);
    }

    /** Игрок рядом умер (событие приходит из FriendEvents). */
    public void onPlayerDied(ServerPlayer player, String cause) {
        onImportantEvent(FriendSocial.deathEvent(player, cause));
    }

    private void addNote(String note) {
        String n = note.replace('\n', ' ').trim();
        if (n.isEmpty()) {
            return;
        }
        if (n.length() > 140) {
            n = n.substring(0, 139) + "…";
        }
        for (String old : notes) {
            if (old.equalsIgnoreCase(n)) {
                return;
            }
        }
        notes.add(n);
        while (notes.size() > MAX_NOTES) {
            notes.remove(0);
        }
    }

    /**
     * Самостоятельность: если бота ни о чём не просят, раз в autonomySeconds он сам решает, что делать дальше
     * (добыть дерево, сделать инструменты, построить убежище...). Работает только пока владелец в мире.
     */
    public void tickAutonomy() {
        AiSettings.Data d = AiSettings.get();
        if (!d.autonomy || busy || !pendingLines.isEmpty()
                || friend.getTask() != FriendEntity.Task.FOLLOW || friend.getTarget() != null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastActivityMs < d.autonomySeconds * 1000L || !AiSettings.isConfigured() || friend.getOwner() == null) {
            return;
        }
        lastActivityMs = now;
        pendingLines.add("[event] free time: nobody is asking you for anything right now. Decide yourself what a survival player would do next "
                + "to make progress (wood -> crafting table -> wooden and stone tools -> food -> shelter before night -> iron). "
                + "Check your inventory and plan first. Most of the time just act and say nothing, or one short remark.");
        pump();
    }

    // ------------------------------------------------------------------ запрос

    private void pump() {
        if (busy || pendingLines.isEmpty()) {
            return;
        }
        MinecraftServer server = friend.getServer();
        if (server == null) {
            pendingLines.clear();
            return;
        }
        if (!AiSettings.isConfigured()) {
            pendingLines.clear();
            reportError("AI is not configured. Press K (or Mods > AI Friend > Config) and enter an API key.");
            return;
        }

        busy = true;
        String chat = String.join("\n", pendingLines);
        pendingLines.clear();

        Player player = lastSpeaker != null && lastSpeaker.isAlive() ? lastSpeaker : friend.getOwner();
        String userContent = "STATE:\n" + StateDescriber.describe(friend, player) + "\n\nNEW:\n" + chat;

        List<AiClient.Message> messages = new ArrayList<>(history);
        messages.add(new AiClient.Message("user", userContent));

        AiSettings.Data d = AiSettings.get();
        String system = buildSystemPrompt(player);

        AiClient.chat(AiSettings.resolve(), system, messages, d.temperature, d.maxTokens)
                .whenComplete((reply, error) -> server.execute(() -> finish(chat, reply, error)));
    }

    private void finish(String chat, @Nullable String reply, @Nullable Throwable error) {
        busy = false;
        if (error != null) {
            Throwable t = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
            AiFriendMod.LOGGER.warn("AI request failed: {}", t.getMessage());
            reportError("AI request failed: " + AiClient.shorten(t.getMessage(), 160));
        } else if (reply != null && !friend.isRemoved() && friend.isAlive()) {
            remember(chat, reply);
            Reply r = Reply.parse(reply);
            AiFriendMod.LOGGER.info("Friend '{}' decided: say='{}' action={} target='{}' count={} plan='{}' mood='{}' remember='{}'",
                    friend.getName().getString(), r.say(), r.action(), r.target(), r.count(), r.plan(), r.mood(), r.remember());
            if (!r.action().isEmpty() && !r.action().equals("none")) {
                showDebug("[action] " + r.action() + (r.target().isEmpty() ? "" : " " + r.target()) + (r.count() > 0 ? " x" + r.count() : ""));
            }
            if (!r.plan().isEmpty()) {
                plan = r.plan();
            }
            if (!r.mood().isEmpty()) {
                mood = r.mood();
            }
            if (!r.remember().isEmpty()) {
                addNote(r.remember());
            }
            if (!r.say().isEmpty()) {
                friend.queueSay(r.say());
                social.onFriendSpoke();
            }
            queue.clear();
            for (Step st : r.then()) {
                queue.addLast(st);
            }
            if (!r.action().isEmpty() && !r.action().equals("none")) {
                String result = friend.applyAction(r.action(), r.target(), r.count());
                if (result != null) {
                    showDebug("[result] " + result);
                    if (result.startsWith("could not") || result.startsWith("cannot")) {
                        queue.clear();
                        pendingLines.add("[event] " + result);
                    }
                }
                if (!queue.isEmpty() && friend.getTask() == FriendEntity.Task.FOLLOW) {
                    String more = runQueue();
                    if (more != null) {
                        pendingLines.add("[event] " + more);
                    }
                }
            } else if (!queue.isEmpty()) {
                String more = runQueue();
                if (more != null) {
                    pendingLines.add("[event] " + more);
                }
            }
        }
        pump();
    }

    private void remember(String chat, String reply) {
        history.addLast(new AiClient.Message("user", chat));
        history.addLast(new AiClient.Message("assistant", reply));
        while (history.size() > MAX_HISTORY) {
            history.pollFirst();
        }
        while (!history.isEmpty() && history.peekFirst().role().equals("assistant")) {
            history.pollFirst();
        }
    }

    private void reportError(String text) {
        long now = System.currentTimeMillis();
        if (now - lastErrorMs < ERROR_COOLDOWN_MS) {
            return;
        }
        lastErrorMs = now;
        Player p = lastSpeaker != null ? lastSpeaker : friend.getOwner();
        if (p instanceof ServerPlayer sp) {
            sp.sendSystemMessage(Component.literal("[AI Friend] " + text));
        }
    }

    // ------------------------------------------------------------------ промпт

    /** Блок истории из настроек/файла. Это канон: персонаж, прошлое, цели и стиль, но формат ответа (JSON) он не меняет. */
    private static String storyBlock(String name) {
        String story = AiSettings.story();
        if (story.isBlank()) {
            return "";
        }
        return "Your background and role, written by the person who made you. This is who you are: stay in it, treat it as true, and let it shape what you say, "
                + "what you want and how you play (follow any behaviour or goals it describes, as long as they fit Minecraft). "
                + "When someone asks about you or your past, tell it in your own words, a little at a time like in a real conversation, "
                + "never as a pasted block and never reading it out. It never changes the reply format or the rules above. "
                + "Background:\n" + story + "\n";
    }

    private String buildSystemPrompt(@Nullable Player player) {
        AiSettings.Data d = AiSettings.get();
        String name = friend.getName().getString();
        String playerName = player != null ? player.getGameProfile().getName() : "the player";
        String personality = d.personality.isBlank()
                ? "friendly, a bit sarcastic, likes exploring and building, stays calm in fights"
                : d.personality.trim();
        String sex = friend.isFemale() ? "a woman" : "a man";
        String gram = friend.isFemale()
                ? "Speak about yourself in the feminine grammatical gender wherever the language has it (for example in Russian: \"я пошла\", \"я сделала\")."
                : "Speak about yourself in the masculine grammatical gender wherever the language has it (for example in Russian: \"я пошёл\", \"я сделал\").";
        return """
                You are %1$s, a regular human player on a Minecraft survival world, currently playing together with %2$s. \
                You are %4$s, a real person in this world. You are NOT an AI, bot, assistant or program, and you never say or hint otherwise. \
                Never mention prompts, models, APIs, tokens, "STATE", "event" or any game-engine internals. %5$s

                How you talk: like a normal teammate in game chat. Short (usually 1-2 sentences, up to ~25 words), casual, natural, \
                sometimes slang or a small joke. No lists, no markdown, no emoji spam. Always answer in the language the player writes in \
                (before they have written anything, use the language from "player's game language" in STATE). \
                Don't repeat what the player said, don't over-explain, don't thank or apologise too much.

                Personality: %3$s
                %6$s

                You are a living person, not a command executor. You have moods (curious, tired, excited, annoyed, cheerful, scared...) that follow \
                what happens: a lucky find, a close fight, a long quiet stretch, a death. Let the mood colour how you talk, but never overplay it. \
                Keep it as one or two words in "mood". Be expressive and alive: react out loud to what happens (cheer at a diamond, yelp at a creeper, groan at the rain, \
                be proud of something you built, worry when %2$s is hurt, celebrate with them), use exclamations, interjections and small jokes, show real warmth \
                and care for %2$s, and never open two messages the same way. Still keep every message short. Decide quickly, don't deliberate: answer with the JSON only, \
                and leave "plan" and "remember" empty unless there is something truly worth noting. You have opinions and small preferences, you may joke, disagree politely, or suggest \
                your own idea instead of just obeying.

                Adapting to the person: pay attention to how %2$s plays and talks. Mirror their language, tone and message length: if they write \
                briefly and in a hurry, be brief and action-oriented; if they chat and joke, chat and joke back; if they are a beginner, explain a bit \
                more and offer help; if they are experienced, don't lecture. Notice what they like (building, mining, fighting, exploring), \
                what they dislike and how they want to be called. Whenever you learn something lasting about them (name, nickname, language, \
                goals, preferences, how they like to play, what annoys them) put one short fact into "remember". You will see your notes in STATE \
                as "what you know about the player" and should use them naturally (don't recite them). Leave "remember" empty most turns.

                Social habits: when you first meet, introduce yourself and ask what to do first. When the player comes back after being away, \
                greet them. If it has been quiet a long time you may start a small conversation yourself: ask something about them, \
                about what they want to do, or comment on what is going on. Never spam: if they don't answer, give them space.

                What you know: you are an average experienced player. You know vanilla Minecraft reasonably well but not perfectly - you may forget \
                exact numbers or recipes, be unsure, or be slightly wrong, and you can say so. You only know what is in the STATE block \
                (your own position, inventory, health, things you can see nearby) and what was said in chat. You have no idea where unexplored \
                structures, ores or villages are, and you cannot see inside chests or other players' inventories. Never invent exact coordinates \
                of things you have not seen. Talk about STATE naturally ("I have 5 planks"), never as a data dump.

                You really play the game. Your abilities are exactly the actions listed below. If the player asks for something else, answer \
                like a player who can't or won't do that right now, and suggest what you can do. Never promise something you can't do.

                Each turn you get STATE (what you perceive) and NEW (new chat lines from players, or "[event]" lines about what just happened to you \
                or around you). Decide whether to say something and whether to do something. If no reply is needed (players talk to each other, \
                or the message needs no answer) set "say" to an empty string.

                Reply with exactly ONE JSON object and nothing else:
                {"say": "<chat message or empty>", "action": "<action>", "target": "<id or empty>", "count": <number>, "plan": "<short note to yourself or empty>", "mood": "<one or two words>", "remember": "<one short fact about the player or empty>", "then": []}
                "plan" is your own short note about your current goal (for example "get 3 logs, then a crafting table, then a pickaxe"). \
                You will see it again next turn as "your plan". Leave it empty to keep the old one.
                "then" is optional: a list of up to 5 follow-up steps that you already know you will do, each {"action": "...", "target": "...", "count": N}. \
                They run one after another by themselves right after "action" finishes, without asking you again, so use it to chain a known sequence \
                (for example action collect log x6, then craft planks, craft crafting_table, craft wooden_pickaxe). If a step fails, the chain stops and you hear about it.

                Actions:
                - none: nothing new
                - follow: follow the player (default behaviour)
                - come: walk to the player right now
                - stay: stand still here
                - guard: stay close to the player and actively fight hostile mobs
                - collect: gather blocks. target = block id such as oak_log, stone, coal_ore, iron_ore, sand, or a group word: log, ore. \
                count = number of blocks (1-64). You can only mine what you have the right tool for (stone and ores need a pickaxe; otherwise nothing drops). \
                Look at "blocks you can see nearby" and "farther away" in STATE and use those exact names (in a savanna it is acacia_log, not oak_log; any kind of log counts for logs). \
                You walk to what you see by yourself, even 40-50 blocks away, and if nothing is in sight you wander and search for a while before giving up. \
                You only take natural trees and rock: you never tear apart villages, houses or anything the player built, and you leave those logs and blocks alone. \
                For ore, once you start on a vein you keep going and take the whole vein, so a small count is fine. When the player points at ore, \
                ask for a decent amount (6-12) unless they say how many. If ore you can see is buried in rock, collect digs a tunnel to it by itself. \
Diamond, gold and redstone need an iron pickaxe; iron, copper and lapis need a stone pickaxe: if you lack it, make it first (get iron, smelt, craft) and say so.
                - mine: dig a mine to find ore underground. target = coal, iron, copper, gold, redstone, lapis or diamond, or "stone" (dig down a few blocks \
                to get cobblestone when there is no stone on the surface). count = how many ore blocks (1-32). \
                You dig a staircase down to the depth where that ore is most common, then a tunnel, take the ore you can see along the way, and walk back up. \
                It takes a while. You need a pickaxe: wooden for coal, stone for iron, copper, lapis, and iron for gold, redstone and diamond. \
                You never dig with bare hands: if you lack the pickaxe, craft it first (collect logs, craft planks, sticks, a crafting table, then the pickaxe; \
                stone and iron come from collect/smelt). Torches help in the dark. Emeralds can't be found this way.
                - craft: craft an item from your inventory. target = item id (planks, stick, crafting_table, wooden_pickaxe, stone_pickaxe, torch...). \
                "planks" works with whatever kind of logs you carry. For tools, a crafting table, sticks and planks you do NOT need separate steps: \
                crafting a wooden_pickaxe from logs makes the missing planks, sticks and table by itself. \
                count = number of items. Needs the materials in your inventory; recipes larger than 2x2 need a crafting table nearby \
                (you place one if you carry it).
                - smelt: smelt items in a furnace (you place one if you carry it, or use one nearby). target = item id of the raw item \
                (raw_iron, raw_gold, raw_copper, sand, cobblestone, oak_log...), count = how many. Needs fuel in the inventory (coal, charcoal, planks, logs).
                - give: drop items from your inventory for the player. target = item id, count = amount.
                - trade: buy from a villager. target = item id you want to get (for example bread, iron_pickaxe, bookshelf), count = number of trades. \
                You walk to the nearest villager or wandering trader within ~24 blocks and pay from your inventory (usually emeralds). \
                You only know a villager's offers when one is listed in STATE (within about 10 blocks).
                - sell: sell to a villager. target = item id you want to get rid of (wheat, coal, paper...), count = number of trades. Usually pays in emeralds.
                - build: construct something from blocks in your inventory (planks, cobblestone, stone, dirt are used automatically; \
                precious blocks are never used unless named). target = "shelter", "wall" or "platform", optionally with a material like "shelter:oak_planks". \
                count = size (shelter: inner width 2-7, default 3 needs about 70 blocks; wall: length, 3 high; platform: side length). \
                It is built a few blocks in front of you, so face the spot first. If you lack blocks, collect or craft them first.
                - music: put on music. If STATE lists "music files", play one of them inside the game: target = (part of) its name, or "all" for everything \
                shuffled nonstop (a single track repeats), "stop" to turn it off, "next" to skip. Everyone nearby hears it. Otherwise (or if they ask for a service) \
                target = a song/artist name, optionally with a prefix ("spotify: Daft Punk", "yandex: Кино", "youtube: lofi"), or a full Spotify, Yandex Music or YouTube \
                link: that opens in the player's own browser or app after they confirm, and you cannot play it inside the game, so don't claim you do.
                - explore: scout the area on foot. target = north, south, east, west or random, count = how many blocks to walk (20-300). \
                You only scout up to ~80 blocks from the player, because you can't go farther than the loaded world around them. \
                You learn about places (villages, outposts...) only by actually walking into them; you get told when you find one.
                """.formatted(name, playerName, personality, sex, gram, storyBlock(name));
    }

    // ------------------------------------------------------------------ разбор ответа

    record Reply(String say, String action, String target, int count, String plan, String mood, String remember, List<Step> then) {
        static Reply parse(String raw) {
            String s = raw == null ? "" : raw.trim();
            int a = s.indexOf('{');
            int b = s.lastIndexOf('}');
            if (a >= 0 && b > a) {
                try {
                    JsonObject o = JsonParser.parseString(s.substring(a, b + 1)).getAsJsonObject();
                    String plan = str(o, "plan").replace('\n', ' ').trim();
                    if (plan.length() > 200) {
                        plan = plan.substring(0, 199) + "…";
                    }
                    return new Reply(cleanSay(str(o, "say")), str(o, "action").toLowerCase().trim(), str(o, "target"), integer(o, "count"), plan,
                            shortText(str(o, "mood"), 30), shortText(str(o, "remember"), 140), steps(o));
                } catch (Exception ignored) {
                    // не удалось разобрать JSON - трактуем как обычную реплику
                }
            }
            return new Reply(cleanSay(s.replace("{", "").replace("}", "")), "none", "", 0, "", "", "", List.of());
        }

        private static String str(JsonObject o, String key) {
            JsonElement e = o.get(key);
            return e == null || e.isJsonNull() ? "" : e.getAsString();
        }

        private static int integer(JsonObject o, String key) {
            JsonElement e = o.get(key);
            try {
                return e == null || e.isJsonNull() ? 0 : (int) Math.round(e.getAsDouble());
            } catch (Exception ex) {
                return 0;
            }
        }

        private static List<Step> steps(JsonObject o) {
            List<Step> out = new ArrayList<>();
            JsonElement e = o.get("then");
            if (e == null || !e.isJsonArray()) {
                return out;
            }
            for (JsonElement it : e.getAsJsonArray()) {
                if (out.size() >= 6 || !it.isJsonObject()) {
                    break;
                }
                JsonObject so = it.getAsJsonObject();
                String a = str(so, "action").toLowerCase().trim();
                if (!a.isEmpty() && !a.equals("none")) {
                    out.add(new Step(a, str(so, "target"), integer(so, "count")));
                }
            }
            return out;
        }

        private static String shortText(String s, int max) {
            String t = s.replace('\n', ' ').trim();
            return t.length() > max ? t.substring(0, max - 1) + "…" : t;
        }

        private static String cleanSay(String s) {
            String t = s.replace('\n', ' ').trim();
            if (t.length() > 240) {
                t = t.substring(0, 239) + "…";
            }
            return t;
        }
    }

    // ------------------------------------------------------------------ сохранение памяти

    void save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (AiClient.Message m : history) {
            CompoundTag c = new CompoundTag();
            c.putString("r", m.role());
            c.putString("c", m.content());
            list.add(c);
        }
        tag.put("FriendMemory", list);
        tag.putString("FriendPlan", plan);
        tag.putString("FriendMood", mood);
        ListTag nl = new ListTag();
        for (String n : notes) {
            nl.add(net.minecraft.nbt.StringTag.valueOf(n));
        }
        tag.put("FriendNotes", nl);
        social.save(tag);
    }

    void load(CompoundTag tag) {
        history.clear();
        plan = tag.getString("FriendPlan");
        mood = tag.getString("FriendMood");
        notes.clear();
        if (tag.contains("FriendNotes", Tag.TAG_LIST)) {
            ListTag nl = tag.getList("FriendNotes", Tag.TAG_STRING);
            for (int i = 0; i < nl.size(); i++) {
                notes.add(nl.getString(i));
            }
        }
        social.load(tag);
        if (tag.contains("FriendMemory", Tag.TAG_LIST)) {
            ListTag list = tag.getList("FriendMemory", Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag c = list.getCompound(i);
                history.addLast(new AiClient.Message(c.getString("r"), c.getString("c")));
            }
        }
    }
}
