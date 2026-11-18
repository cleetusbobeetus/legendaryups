tag @s remove dp_play
tag @s remove dp_winpending
tag @s add dp_inwin
gamemode adventure @s
effect clear @s
execute in minecraft:overworld run teleport @s 1000.5 161 1070.5 facing 1000.5 166.5 1061.4
function dropper:beat_game_rng_stats
title @s title {"text":"REALITY CONQUERED","color":"gold","bold":true}
title @s subtitle {"text":"Choose your fate","color":"yellow"}
