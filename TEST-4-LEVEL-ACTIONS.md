# Testing the 4 New Level Actions

This guide walks you through testing the new **Level Up**, **Level Down**, **Raw Level**, and **Experience** actions added to LegendaryUpsBridge.

---

## 🚀 Quick Start: Build & Deploy

### Step 1: Build and sync the updated plugin
```batch
BUILD-AND-TEST.bat
```

This will:
1. ✅ Build the plugin from source (runs all tests)
2. ✅ Copy the JAR to `plugins/`
3. ✅ Show you the next steps

### Step 2: Reload the plugin
In-game (as server operator), run **one** of:

```
/reload confirm
```
OR stop and restart the Paper server.

---

## ✅ Manual Tests (In-Game)

After reload, test each action individually:

### Test 1: Level Up
```
/lub test levelup
```
**Expected:** Your experience level increases by 1
- Your level bar moves up
- If you're at level 30, you go to 31
- Experience value shown in top-right corner increases

### Test 2: Level Down
```
/lub test leveldown
```
**Expected:** Your experience level decreases by 1
- Your level bar moves down
- If you're at level 30, you go to 29
- Experience value shown in top-right corner decreases

### Test 3: Raw Level (Set to Specific Level)
```
/lub test level
```
**Expected:** Your level is set to 10 (default test value)
- Your level bar updates to level 10
- Experience value resets to match level 10
- Works regardless of your current level

### Test 4: Give Experience
```
/lub test exp
```
**Expected:** You gain 100 experience points (default test value)
- Your experience bar fills up
- If the experience fills a whole level, you level up
- Level number stays the same until you have enough total XP for the next level

---

## 🎮 Full Integration Test (With TikTok Gifts)

Once the basic tests pass, configure a game trigger to test with live gifts:

### Setup Gift Triggers
1. Open **LegendaryUps launcher** → Pick a game (e.g., "Infinite Dropper")
2. Go to **Interactions** tab
3. Create triggers for each level action:

#### Trigger 1: Gift = Rose → Level Up
- **Trigger:** Gift (Rose)
- **Action:** `levelup` (Level Up)
- **Parameter:** Default (adds 1 level)

#### Trigger 2: Gift = Doll → Level Down
- **Trigger:** Gift (Doll)
- **Action:** `leveldown` (Level Down)
- **Parameter:** Default (subtracts 1 level)

#### Trigger 3: Gift = Drama → Set Level to 20
- **Trigger:** Gift (Drama)
- **Action:** `level` (Raw Level)
- **Parameter:** `20` (set level to 20)

#### Trigger 4: Gift = Fireworks → Give Experience
- **Trigger:** Gift (Fireworks)
- **Action:** `exp` (Experience)
- **Parameter:** `250` (give 250 XP)

### Run Live Test
1. **Start TikFinity LIVE** and connect the launcher (Connections → TikFinity Connect)
2. **Join the game** in-game as your target player
3. **Send gifts from another account** (or ask a viewer):
   - Send Rose → You level up by 1
   - Send Doll → You level down by 1
   - Send Drama → Your level jumps to 20
   - Send Fireworks → Your XP bar fills (may auto-level)

### Expected Results
- Each gift triggers the corresponding action **immediately**
- Launcher shows **CONFIRMED** for each action
- Level changes are visible in-game on your experience bar

---

## 🧪 Test Cases Checklist

- [ ] `/lub test levelup` → Level increases by 1
- [ ] `/lub test leveldown` → Level decreases by 1 (not below 0)
- [ ] `/lub test level` → Level sets to 10
- [ ] `/lub test exp` → Experience bar fills by 100 XP
- [ ] Gift: Rose triggers Level Up action
- [ ] Gift: Doll triggers Level Down action
- [ ] Gift: Drama triggers Set Level to 20 action
- [ ] Gift: Fireworks triggers Give Experience action
- [ ] All actions show **CONFIRMED** in launcher logs
- [ ] No errors in Paper server console
- [ ] Multiple actions queue and execute in order

---

## 🐛 Troubleshooting

### "Command failed" or "Nothing happens"
- ✅ Check in-game: `/lub status` → Bridge: listening?
- ✅ Check launcher Connections: Paper server folder selected?
- ✅ Check launcher: Target player online?
- ✅ Check Paper console for error messages

### Level doesn't change
- ✅ Check launcher logs: Does action show **CONFIRMED**?
- ✅ Verify you're the right player (check Connections username)
- ✅ Try `/lub test levelup` manually first

### JAR not updating
- ✅ Run `BUILD-AND-TEST.bat` again
- ✅ Check `plugins/` folder has the latest JAR
- ✅ Restart the server or run `/reload confirm`

### Experience doesn't work
- ✅ Check if `exp` action is registered: `/lub games`
- ✅ Increase XP amount in trigger (try 250 instead of 100)
- ✅ Your level may max out at 32,767 (MC limit)

---

## 📊 Automation Hook

The `.claude/settings.json` hook automatically syncs the plugin after builds:

```json
{
  "hooks": {
    "PostToolUse": [
      {
        "matcher": "Bash",
        "hooks": [
          {
            "command": "Copy latest JAR to plugins/ folder",
            "statusMessage": "🔄 Syncing plugin to Paper server..."
          }
        ]
      }
    ]
  }
}
```

**How it works:**
1. When you edit/save plugin files
2. Run the build command
3. The hook automatically copies the JAR to `plugins/`
4. Reload the server and test

---

## 📝 Notes for Future Development

- Level Up/Down actions use **Bukkit Experience API** (`setLevel()`)
- Experience action uses `giveExp()` → adds to current total XP
- Raw Level can set any value 0–2,147,483,647
- Experience values follow Minecraft's vanilla XP formula
- All actions run as op-level commands (no permission issues)

---

**Happy testing! 🎮**

For more help, see `SETUP.md` or run `/lub help` in-game.
