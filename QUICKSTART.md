# ⚡ Legendary Ups Parkour Server - Quick Start

Your **fully automated setup** for building, deploying, and testing the LegendaryUpsBridge plugin with the new 4 Level actions.

---

## 🎯 What You Have

| Component | Location | Status |
|-----------|----------|--------|
| **Paper 1.21.10 Server** | This folder | ✅ Running |
| **LegendaryUpsBridge Plugin** | `../../../LegendaryUps-v0.6.1/` | ✅ Source ready |
| **Build Automation** | `BUILD-AND-TEST.bat` | ✅ Ready |
| **Claude Integration** | `.claude/settings.json` | ✅ Hook configured |
| **New Actions** | `levelup`, `leveldown`, `level`, `exp` | ✅ Implemented |

---

## 🚀 One-Command Setup & Deploy

### **Step 1: Build and sync the plugin**
Double-click or run:
```batch
BUILD-AND-TEST.bat
```

**What it does:**
1. ✅ Builds the plugin from source (all tests run)
2. ✅ Copies JAR to `plugins/` folder
3. ✅ Shows you what to do next

**Takes:** ~30 seconds (first build: ~1 minute for Gradle + Paper API download)

---

## 🔄 Reload the Server

After the build completes, **reload the plugin** with ONE of these:

### **Option A: In-Game Reload (Faster, No Downtime)**
In Minecraft, as server operator:
```
/reload confirm
```

This reloads all plugins without stopping the server. You stay connected.

### **Option B: Restart Server (Full Reset)**
1. Stop the Paper server
2. Start it again
3. Wait for `[LegendaryUpsBridge] Bridge listening...` in console

---

## ✅ Verify Everything Works

### **Quick Test (60 seconds)**
In-game, run these commands one-by-one:
```
/lub status              → Should show "Bridge: listening on 127.0.0.1:25590"
/lub test levelup        → Your level increases by 1
/lub test leveldown      → Your level decreases by 1
/lub test level          → Your level becomes 10
/lub test exp            → You gain 100 XP
```

If all show **action executed**, you're good to go! ✅

---

## 🎮 Test With TikTok Gifts

Once basic tests pass, test with real TikTok gifts:

1. Open **LegendaryUps launcher** (the Windows app)
2. Pick a game (e.g., "Infinite Dropper")
3. Go to **Interactions** tab
4. Create gift triggers:
   - **Gift: Rose** → **Action: levelup** (adds 1 level)
   - **Gift: Doll** → **Action: leveldown** (subtracts 1 level)
   - **Gift: Drama** → **Action: level** (sets to level 20)
   - **Gift: Fireworks** → **Action: exp** (gives 250 XP)
5. **Go LIVE** with TikFinity
6. Send gifts from another account → Watch effects happen instantly

**Expected:** Launcher shows **CONFIRMED** for each gift action, and your level changes in-game.

---

## 🛠️ Full Test Guide

For detailed test procedures, edge cases, and troubleshooting:

👉 **[TEST-4-LEVEL-ACTIONS.md](TEST-4-LEVEL-ACTIONS.md)**

This covers:
- Each action's expected behavior
- Integration with TikTok gifts
- Troubleshooting steps
- Test checklist

---

## 📂 Project Structure

```
LegendaryUps-Parkour-Test-1.21.10/           (📍 You are here)
├── server.properties                         ← Server config
├── paper.jar                                 ← Server JAR
├── plugins/                                  ← Plugin folder
│   ├── LegendaryUpsBridge-1.0.0.jar         ← Your plugin (auto-synced)
│   ├── DropperRNG-2.2.0.jar
│   └── ...
├── world/                                    ← Game world
├── .claude/
│   └── settings.json                         ← Build hook (auto-syncs JAR)
├── BUILD-AND-TEST.bat                       ← 🚀 Use this to build & deploy
├── TEST-4-LEVEL-ACTIONS.md                  ← Detailed tests
└── QUICKSTART.md                             ← You are here

LegendaryUps-v0.6.1/
└── LegendaryUps/
    ├── BUILD-PLUGIN.bat                      ← Build the plugin
    ├── START-WINDOWS.bat                     ← Run the launcher app
    └── plugin/
        ├── src/main/java/                    ← Plugin source code
        ├── build/libs/                       ← Compiled JAR
        └── dist/                             ← Distribution copy
```

---

## ⚙️ Automation: Claude Integration

The `.claude/settings.json` file contains a **PostToolUse hook** that:
- 🔄 Watches for file changes in the plugin source
- ⚡ Automatically copies the built JAR to `plugins/`
- ✅ Shows sync status

**You don't need to do anything** — just edit plugin code, build, and the hook syncs automatically.

---

## 🧰 Commands Reference

### **Build & Deploy**
```bash
BUILD-AND-TEST.bat          # Build, sync, and show next steps
```

### **In-Game Plugin Commands** (as op)
```
/lub status                 # Check bridge connection
/lub test <action>          # Test an action
/lub games                  # List available games
/lub test levelup           # Test: level += 1
/lub test leveldown         # Test: level -= 1
/lub test level             # Test: level = 10
/lub test exp               # Test: xp += 100
```

### **Server Reload**
```
/reload confirm             # Reload all plugins (no restart)
```

---

## 📋 Workflow: Edit → Build → Test

**Typical workflow:**

1. **Edit plugin source** in `LegendaryUps-v0.6.1/LegendaryUps/plugin/src/`
2. **Run:** `BUILD-AND-TEST.bat`
3. **Reload in-game:** `/reload confirm`
4. **Test in-game:** `/lub test <action>`
5. **Test with gifts:** Send TikTok gifts in launcher

That's it! The hook handles copying the JAR.

---

## 🐛 If Something Goes Wrong

| Problem | Fix |
|---------|-----|
| Build fails | Check you have Node.js 20+ and Java 21+ installed |
| Plugin won't load | Check `plugins/` folder for the latest JAR, restart server |
| Actions don't work | Run `/lub status` → check bridge is listening |
| Level doesn't change | Check launcher shows **CONFIRMED**, verify you're online |

**More help:** See [TEST-4-LEVEL-ACTIONS.md](TEST-4-LEVEL-ACTIONS.md#-troubleshooting)

---

## 🎓 Original Documentation

For complete setup, configuration, and advanced topics:

- **[SETUP.md](SETUP.md)** — Full installation & configuration guide
- **[Main README](../../../LegendaryUps-v0.6.1/LegendaryUps/README.md)** — Project architecture

---

## ✨ You're Ready!

```
✅ Plugin builds automatically
✅ JAR syncs to server automatically
✅ 4 new level actions ready to test
✅ Full TikTok integration ready
```

**Next step:** Run `BUILD-AND-TEST.bat` and test the level actions! 🚀
