# AI Friend

**A companion who plays Minecraft like a real person — and talks to you like one.**

AI Friend adds a bot-player powered by the AI model of *your* choice. He follows you, fights, chops trees, mines ore, crafts, smelts, trades with villagers, builds a shelter and scouts the area. You talk to him by typing in chat or with your own voice, and he answers in text and (optionally) out loud, in his own voice, when he's standing near you.

He isn't an all-knowing command executor. He only knows what a player would know: his inventory, his health, what he can see around him and what he has found by walking there. He makes mistakes, notices danger, asks you things, remembers what you talked about and adapts to how you play.

## Features

**A character, not a command list**
- Male by default (random name), female with a switch; custom skins from a PNG file.
- Greets you when you come back, asks "where do we start?" on the first meeting, and starts a small conversation if it has been quiet for a while.
- Has moods and a short memory of notes about you and your world.
- Talks in your language (he follows your game language).

**Plays the game**
- Follow, stay, guard, fight nearby monsters.
- Collect logs, stone and ores. Sees trees and veins from far away, walks to them, takes a whole vein, and **digs a tunnel to ore that is buried in rock**.
- Makes the tools he needs himself (planks, sticks, crafting table, pickaxes...) and knows which pickaxe an ore needs.
- Mines underground for coal, iron, copper, gold, redstone, lapis and diamonds, avoiding lava and water.
- Smelts, crafts, trades and sells to villagers, builds simple shelters, walls and platforms, explores and tells you what he finds.
- Never breaks villages, houses or your builds — only natural trees and rock.
- Chains several steps from a single request ("chop some wood, then make a pickaxe").

**Voice (optional)**
- Talk to him with push-to-talk (default **V**) or voice activation.
- Text-to-speech: ElevenLabs, Fish Audio or any custom HTTP service.
- Speech-to-text: OpenAI Whisper, Groq, ElevenLabs Scribe or any custom service.
- Choose your microphone and your audio output device; his voice is spatial (louder and panned by where he stands) and only plays when he is near.

**Music on request**
- Drop your own `.mp3` / `.ogg` / `.wav` files into `config/aifriend/music` and ask him to play them: the music plays inside the game (shared with nearby players who have the same file), ducks while he talks, and a vinyl record with the cover art spins on the side of your screen.
- Ask "put on some music" or hand him a link: he opens Spotify, Yandex Music or YouTube in your browser or app (after you confirm). The mod itself never downloads or rips audio.

**Your keys, your choice of AI**
- Official providers or any custom OpenAI/Anthropic-compatible API. You enter your own API keys in the in-game settings (**K**). Nothing is sent anywhere except to the services you configure.

## Getting started

1. Install NeoForge 1.21.1 (Java 21), drop the mod into `mods`.
2. Launch the game, press **K**, choose a provider and paste your API key.
3. Join a world — your Friend appears on his own. (`/friend spawn` creates another one.)
4. Talk in chat, or hold **V** to speak.

## Commands

`/friend spawn [name]` · `/friend dismiss` · `/friend name <name>` · `/friend gender male|female` · `/friend skin <id>|list` · `/friend status` · `/friend reload`

## Good to know

- MP3 playback uses the open-source JLayer decoder (LGPL 2.1), bundled via Jar-in-Jar.

- You need your own API key for an AI provider (and optionally for a voice service). Those services are billed by their providers, not by the mod.
- Works in single player and over LAN. On LAN, the host's key is used for the AI.
- Currently supports Minecraft 1.21.1 with NeoForge.
- Report bugs with `logs/latest.log` — lines starting with `Friend '...'` show what the bot decided and why.
