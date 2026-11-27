**[⬇ Download the latest version / Скачать последнюю версию](https://github.com/jumonja-maker/FreshGenWorld/releases/latest)**

# FreshGenerator

A world-generation mod for **Minecraft Java 26.3** (Fabric). It reshapes terrain, climate, biome borders, forests, glaciers and caves toward natural, logical landscapes and carves rivers, lakes and waterfalls from a drainage model, while keeping native Minecraft biomes, structures and blocks.

**[English](#english) · [Русский](#русский)**

---

## English

### Install

1. Install **Fabric Loader 0.20.1+** and **Fabric API** for Minecraft **26.3**. Java **25** is required.
2. Download `FreshGenerator-Fabric-26.3-<version>.jar` from the [latest release](https://github.com/jumonja-maker/FreshGenWorld/releases/latest) and put it into the `mods` folder.
3. Create a new world with the normal **Default** world type: FreshGenerator replaces it, no extra option to pick.

Existing chunks are never rebuilt; new terrain appears only in new chunks. **Start a new world for 0.9.4:** the world is deeper (down to Y-112), and mountains, deserts, villages, roads, volcanoes and caves changed, so continuing an older world leaves seams where old and new chunks meet. The mod is in beta: back up your worlds.

**Compatibility notes.** FreshGenerator replaces the `minecraft:normal` (Default) world preset. In its own world it switches off all cave carvers (including carvers added by other mods) and uses its own sets of shipwrecks, ocean ruins and monuments. Since 0.9.4 it also uses its own village and pillager outpost sets (`freshgenerator:villages`, `freshgenerator:pillager_outposts`, built from the vanilla lists): mods or datapacks that change the vanilla `minecraft:villages` or `minecraft:pillager_outposts` sets have no effect in a FreshGenerator world. Surface structures of other mods are not checked against its rivers and lakes. Its mixins wrap parts of chunk generation (`NoiseChunk`, `NoiseBasedChunkGenerator`, `ChunkGenerator`, `ServerLevel`, `FeaturePlacer`); mods that rewrite the same code (for example C2ME) and Distant Horizons have not been tested with it.

### What's new in 0.9.4 (beta)
- **Generation fixes:** a valley floor under a very high icy peak could read its biome from above the top of the world. In 26.3 this stopped the area from ever generating (`Exception generating new chunk`) or wrote the cave biome `deep_dark` into the gorge, with grass on the ice. The bug was also in the published 0.9.3. A small volcanic vent could stop the generation of its chunk; fixed.
- **Steep mountain streams** run in a V-shaped rocky gully instead of a slot with vertical walls 20+ blocks high. Rivers from 5 blocks wide get the river biome (before: 7), and frozen rivers follow the cold at the water's own height.
- **Fewer villages, no clusters:** villages stand at least ~340 blocks apart (no more several villages on one meadow), and each cell of the village layout holds a village only with a 60% chance: neighbours and wide empty stretches alternate at random instead of a village every so many cells. Houses are never built on rivers, lakes or the sea; a village whose centre would stand on water is not built at all. Streets crossing water keep the native plank rule, but only where they join inhabited parts of a village: dead-end plank piers are gone. Pillager outposts, igloos, jungle and desert temples, trail ruins and land ruined portals are not placed on water either.
- **Roads between villages:** about half of the villages are joined to a neighbour by a worn path of native village street blocks (dirt path, smooth sandstone in deserts) that winds with the terrain, breaks off and resumes, climbs mountains in switchbacks or not at all, and never reaches island villages. The path stays walkable (no steps higher than a block), crosses streams instead of running along their banks, keeps off ruined portals and other structures, never meets a lava pool, and is not built in worlds with structures turned off.
- **Bridges** of native blocks where a road meets a river: a fallen log, stepping stones, plank boardwalks, wooden and stone bridges — intact, worn or ruined. A bridge spans the river across its channel and leads onto both banks (stairs down to a low bank, a step cut into a high one); every block of a worn or ruined bridge rests on a support, and the wood matches the biome.
- **No lava holes on the surface:** the native surface lava lakes are gone (underground ones stay). Instead, rare volcanic vents: a small elongated basalt cone with lava in its crater in deserts, a lava pit sunk into the ground in badlands.
- **Mountain ranges:** long continuous ranges with spurs, passes and through-valleys instead of single mountains standing in a field; single knobs away from the belts became hills.
- **Wider deserts:** the hot belt is about twice as large; deserts are big continuous regions with sand seas of real dunes (prevailing wind per world), rocky tablelands and inselbergs.
- **Natural snow and forest borders:** the snow and taiga borders break into lobes and outliers and follow the relief (hills first), instead of ruled lines.
- **Volcanoes and impact craters:** rare volcanic provinces with stratovolcanoes (the rare giant reaches ~300), shields, calderas with lakes and cinder fields; impact craters of three ages, cut as a crescent into a slope, with a burnt, felled forest around fresh ones.
- **Active volcanoes:** a third of the cones are active: a crusted lava lake in a pit on the crater floor, fresh black lava flows traced down the real slopes (they follow gullies and valleys, spread wide on flat ground, end in lobes and can dam a valley), bare ash on the upper cone, a belt of charred standing trunks below it, no snow on the crater and the fresh flows. No village, outpost or temple is built on a volcano's cone, in a fresh crater or by a rift.
- **Volcanic islands and seamounts:** volcanoes on the sea floor rise as bare volcanic islands with beaches (some active, with a lava lake) or stay under the sea as seamounts of basalt and tuff with glowing vents.
- **Rifts:** chasms from a few hundred blocks to three kilometres long and up to 170 deep, never below Y8. Their floor follows the low ground along their line, so a rift crossing a mountain splits it. They cross plains as well as mountains, stay dry inside and away from the sea; rivers and lake outlets pour into them as waterfalls into a plunge pool on the floor (icefalls in the frozen north).
- **Deeper world and new caves (new worlds only):** the world reaches Y-112. Cave levels that come and go and tilt, spongework, vadose canyons, pits, mazes and great breakdown chambers with dozens of passages leaving their walls. Cave entrances stay rare (ground H ≥ 73).
- **Escarpments:** cliff bands on plateau edges and mountain flanks, where rivers fall as waterfalls.
- **Big waterfalls:** a stream that meets a steep face leaves its top as one waterfall of 12-40 blocks (two or three in a row on a long face) over a plunge pool in a short gorge, instead of running down the face in riffles. In frozen mountains the whole curtain is an icefall.
- **Village details** such as lamps and flower beds stand on the ground: none hangs in the air above a graded slope.
- **Water and landforms agree:** no walls of sea water beside rifts or in craters and calderas below sea level (a closed bowl stays dry or holds its own lake), no moat round a volcano, no springs or lava pouring out of rift walls, dry domes in the great chambers without lava falls at their edges, no cliffs across a valley side below a waterfall.
- **Volcanic islands** carry land biomes (forest, taiga, beaches), also for `/locate biome`; shipwrecks, ocean ruins and monuments stay in the water around them.

### In 0.9.3
- **Rivers, lakes and waterfalls:** a drainage model of the whole landscape (flow routing, catchments, lake basins) decides where water runs. Streams start at real channel heads, join into rivers that widen downstream and reach the sea through broad valleys; lakes fill closed basins up to a level proven watertight block by block; waterfalls sit at real drops. The model is computed when chunks generate and is never saved: the same seed always gives the same water.
- **Climate shape:** caves share the climate of the surface above them, climate borders meander instead of running straight, and a small island takes the climate of most of its land.
- **Search:** `/locate biome minecraft:river` finds wide rivers (7+ blocks) within about 2 km of the player at any height and reports the river itself.

### In 0.9.2
- **Much faster generation:** chunks generate about 4–14 times faster. The disabled vanilla cave carvers no longer query the climate of ~289 neighbouring chunks for every chunk; the generated blocks are identical.
- **Snow line by height:** mountain biomes switch along height contours — snowy slopes from about Y168 and peaks from Y212 (25 blocks lower in cold regions), whatever the local erosion. Snowy slopes and peaks no longer stand next to forest, meadow or cherry groves at the same height, and grove or snowy slopes no longer sit in low valleys.
- **Warm mountains:** rocky stony peaks from about Y157 with snowy summits above them; a forest never appears above a rocky summit.
- **Logical frozen regions:** frozen land is snowy plains, snowy taiga or snowy beach right up to the climate border — no more ring of rainy taiga and rainy stony shores along every frozen region, and no green, rainy windswept hills or forests inside snowy taiga.
- **Glaciers:** ice caps on high cold plateaus keep real thickness (up to ~18 blocks) under snow; glaciers lie on the real ground instead of burying snow and ice inside mountain slopes; no spruces growing on glacier ice; snow cover returns where a crevasse was removed.
- **Oceans:** shipwrecks and ocean ruins keep clear of monuments; seagrass and kelp check their real water depth next to structures on the floor; no sand/clay seam between lukewarm and warm oceans.
- **Caves:** surface mountain belts no longer change deep cave biomes (deep dark, sulfur caves); deep caves use the same climate as the native aquifer guard.
- **Debug screen:** F3 shows `FG lookup E` / `FG lookup W`, the erosion and weirdness the biome table really uses.

### In 0.9.1
- **Great snow massifs:** large cold regions rise into ranges of varied peaks in their cores; small snowy regions stay hills.
- **Mountain biomes follow real height** in every mountain: forest and meadow below, snowy slopes and grove above, frozen and jagged peaks on the summits.
- **Logical cold transitions:** forests and plains reach snowy plains through a belt of taiga; cold coasts are snowy beaches; very humid frozen land is snowy taiga instead of rainy taiga; cool high plateaus carry montane forest instead of savanna or jungle beside snow.
- **New oceans:** abyssal basins, trenches, mid-ocean ridges and seamounts; basalt on volcanic ground, clay in basins, sediment several blocks thick; seagrass, kelp and reefs follow light and ground; fewer underwater structures, monuments stand on their native pillars.
- **Better coasts and giant mountains:** no 80-block sea walls, gentler giant flanks, bare rock instead of soil on near-vertical walls.
- **Caves:** collapse dolines and winding potholes sit on real junctions of two galleries and are sized in blocks; no more long rifts or shafts broken into closed pockets.
- **Villages:** smooth banks at the water's edge.

### How the generator works

Everything is built from Minecraft's own density functions, noise and material rules, compiled into a datapack at build time. Runtime hooks handle forests, glaciers, the drainage model of rivers and lakes (it decides where water stands when chunks generate; the game's own fluid rules then run unchanged) and village roads. The mod adds no new biomes or blocks. Native biome selection, structures, aquifers and fluids stay in charge.

**Terrain.** One continuous height envelope replaces the native 3-D surface noise, so there are no floating islands or overhanging slabs. On top of that envelope:
- regional landforms: massifs, ridges, plateaus with broad foothill aprons, and rare giant mountain provinces that rise on wide flanks instead of sheer walls;
- great snow massifs in the cores of large cold regions: the deeper inside the cold zone, the higher the mountains (a small snowy region gets hills, a large one a range of varied peaks), with altitude belts of snowy plains and taiga, snowy slopes and grove, then frozen and jagged peaks; all mountains follow such belts, so peaks no longer stand next to forest at the same height;
- coasts that climb inland under a coastal ceiling: resistant rock keeps steep sea cliffs, soft coasts slope gently, with no 80-block walls straight out of the sea;
- quiet eroded lowlands;
- a smooth height ceiling instead of a flat cut.

Added relief never drowns dry land below sea level, so there are no fields of puddles with grass floors. Soil does not cling to near-vertical walls: in grass-topped biomes such slopes turn into exposed rock.

**Biome-defined relief.** Where the climate selects a landform biome, the terrain follows the same climate fields:
- badlands become stepped mesas, with benches of +24/+50/+80 blocks and canyons between them, and layered terracotta cliffs;
- windswept areas become real hills, up to +44 blocks.

**Climate.** Temperature and humidity regions are wide, so a biome covers a real region instead of small speckles. A smooth mid-scale bend keeps their borders natural. Temperature drops with altitude.
- **Ecological niches:** rainforest reaches desert only through a narrow belt of savanna and dry forest; swamps and mangroves stay near the water surface; cool high plateaus just below the snow line carry montane forest, not savanna or jungle; forests and plains reach snowy plains through a belt of taiga.
- **Large and small biomes:** large climate biomes (plains, forests, taiga, deserts, oceans, snowy plains) form large regions; special ones (mushroom fields, cherry groves, pale garden, ice spikes) stay small and rare. Climate zones are very large, so hot deserts and badlands can be many kilometres away from spawn — on purpose, as in real geography.

**Caves: a karst system** modelled on real limestone caves.
- **Three stacked levels** like former water tables: upper ~Y 6, main ~Y −24, deep ~Y −47.
- **Galleries** follow two shared "joint plan" noises, so passages are continuous and meet at junctions. Main galleries are 14–18 blocks wide, opening into domed halls 40–60 wide with full-height stone pillars.
- **Transitions down** go through bell-shaped pits on the passages and rare great shafts through all levels.
- **Main level:** vadose "keyhole" canyons cut below its floor. **Deep level:** anastomotic maze zones and native lava lakes at the very bottom.
- **Rare entrances (about 0.2–0.5 per km²)**, only on dry ground above Y 73, so sea-level water never meets an open cave. Three kinds:
  - round collapse dolines over a junction of two galleries;
  - inclined passages that start as a roofless gorge;
  - potholes on high plateaus that wind downward like real deep shafts: the shaft swings around a slow, irregular spiral, so short drops alternate with ledges instead of one straight fall.
- Everywhere else at least 14 blocks of rock cover the caves. Native carvers are disabled; this prevents caves breaking the surface and leaving hanging water.

**Forests.** Native tree features stay, but candidates are thinned and resized by ecology:
- moisture, slope, exposure, treeline and forest edges;
- smooth ecotones between forest types, including thinning toward treeless neighbours.

Crowns may narrow but never grow past the native radius, so generated leaves do not decay. Trees keep their native soil, space and survival checks.

**Oceans.** The shelf stays a quiet sedimentary plain; the deep floor is split into abyssal basins with rare trenches, mid-ocean ridge networks with a central rift, and isolated rounded seamounts of different heights that rise toward the light. Ridges and seamounts are young volcanic ground: their walls and crests expose smooth basalt. Elsewhere steep underwater walls expose rock, slopes carry gravel, flats hold sand (warm seas), gravel (cold seas) and clay, which collects in deep basins; the sediment lies several blocks thick. Seagrass, kelp and warm reefs thin out with depth; kelp grows in patchy forests on hard ground, and reefs prefer rock. Shipwrecks, ocean ruins and monuments are rarer; monuments keep their native foundation pillars down to the sea floor.

**Glaciers and icebergs.**
- Mountain ice fills cold, sheltered valleys, with moraines and crevasses.
- Icebergs follow a continuous temperature curve with no cut at ocean borders.

**Villages.** Village plots are graded smoothly into the terrain, without dams over water; a raised plot at the water's edge gets a short natural bank instead of a vertical step.

### Build from source

Java 25, Gradle 9.8.2:

```bash
gradle build
```

If a dependency cannot be resolved, add `--init-script scripts/central-fallback.init.gradle` (the same fallback CI uses). The JAR is written to `build/libs`. The build compiles the worldgen pack (`src/tool`) and runs all Java tests; the published JARs in `dist/` are fixtures of those tests. `src/audit` and `src/smoke` contain test-only server observers.

---

## Русский

### Установка

1. Установите **Fabric Loader 0.20.1+** и **Fabric API** для Minecraft **26.3**. Нужна Java **25**.
2. Скачайте `FreshGenerator-Fabric-26.3-<версия>.jar` из [последнего релиза](https://github.com/jumonja-maker/FreshGenWorld/releases/latest) и положите в папку `mods`.
3. Создайте новый мир в обычном режиме **«По умолчанию»**: его создаёт FreshGenerator, отдельно выбирать ничего не нужно.

Существующие чанки не перестраиваются: новая генерация появляется только в новых чанках. **Для 0.9.4 создайте новый мир:** мир стал глубже (до Y−112), изменились горы, пустыни, деревни, дороги, вулканы и пещеры, и в старом мире на стыке старых и новых чанков останутся швы. Мод в стадии beta, делайте резервные копии миров.

**Совместимость.** FreshGenerator заменяет набор мира `minecraft:normal` («По умолчанию»). В своём мире он выключает все карверы пещер (в том числе добавленные другими модами) и использует свои наборы кораблекрушений, подводных руин и монументов. С 0.9.4 он использует и свои наборы деревень и аванпостов разбойников (`freshgenerator:villages`, `freshgenerator:pillager_outposts`, собраны из ванильных списков): моды и датапаки, которые меняют ванильные наборы `minecraft:villages` или `minecraft:pillager_outposts`, в мире FreshGenerator не действуют. Наземные постройки других модов с его реками и озёрами не сверяются. Его миксины оборачивают части генерации чанков (`NoiseChunk`, `NoiseBasedChunkGenerator`, `ChunkGenerator`, `ServerLevel`, `FeaturePlacer`); моды, которые переписывают тот же код (например, C2ME), и Distant Horizons вместе с ним не проверялись.

### Что нового в 0.9.4 (beta)
- **Исправлены сбои генерации:** дно долины под очень высоким ледяным пиком могло читать биом из ячейки выше верха мира. В 26.3 участок из-за этого не генерировался никогда (`Exception generating new chunk`) или в ущелье записывался пещерный биом `deep_dark` с травой посреди льда. Ошибка была и в опубликованной 0.9.3. Малое вулканическое жерло могло остановить генерацию своего чанка — исправлено.
- **Крутые горные ручьи** текут в каменной V-образной промоине вместо щели с отвесными стенами в 20 с лишним блоков. Биом реки получают русла от 5 блоков (было от 7), замёрзшая река определяется холодом на высоте самой воды.
- **Меньше деревень, без скоплений:** не ближе ~340 блоков друг к другу (больше нет нескольких деревень на одном лугу), и каждая клетка раскладки получает деревню лишь с шансом 60%: соседние деревни и широкие пустые места чередуются случайно, а не «через столько-то клеток». Дома не строятся на реках, озёрах и море; деревня, центр которой оказался бы на воде, не строится. Улицы через воду — по ванильному правилу, из досок, но только как переход между жилыми частями деревни: тупиковых дощатых пирсов больше нет. Аванпосты разбойников, иглу, храмы джунглей и пустыни, руины троп и наземные разрушенные порталы тоже не ставятся на воду.
- **Дороги между деревнями:** примерно половина деревень соединена с соседней протоптанной дорогой из блоков деревенских улиц (тропа, в пустыне гладкий песчаник); она следует рельефу, обрывается и продолжается, в горы поднимается серпантином или не идёт вовсе, к островным деревням не ведёт. По тропе можно пройти без прыжков выше блока, ручьи она пересекает, а не идёт по их кромке, разрушенные порталы и другие постройки обходит, в лаву не ведёт; в мире с выключенными структурами дорог нет.
- **Мосты** из ванильных блоков там, где дорога встречает реку: упавшее бревно, камни брода, мостки, деревянные и каменные мосты — целые, изношенные и разрушенные. Мост ложится поперёк русла и выводит на оба берега (ступени вниз к низкому берегу, ступень в край высокого), каждый блок изношенного и разрушенного моста держится на опоре, дерево — по биому.
- **Нет лавовых дыр на поверхности:** родные поверхностные лавовые озёра убраны (подземные остались). Вместо них редкие вулканические жерла: в пустыне небольшой вытянутый базальтовый конус с лавой в кратере, в бедлендах — лавовая воронка, уходящая в землю.
- **Горные хребты:** длинные связные хребты с отрогами, перевалами и сквозными долинами вместо одиночных гор посреди поля; одиночные бугры вдали от поясов стали холмами.
- **Шире пустыни:** жаркий пояс примерно вдвое больше; пустыни — большие цельные области с песчаными морями из настоящих дюн (ветер свой у каждого мира), каменистыми плато и останцами.
- **Естественные границы снега и леса:** граница снега и тайги распадается на лопасти и острова и следует рельефу (холмы первыми), а не идёт по линейке.
- **Вулканы и метеоритные кратеры:** редкие вулканические провинции со стратовулканами (редкий гигант до ~300), щитовыми вулканами, кальдерами с озёрами и полями шлаковых конусов; кратеры трёх возрастов, на склоне вырезанные полумесяцем, вокруг свежих — выжженный поваленный лес.
- **Активные вулканы:** треть конусов активна: лавовое озеро с коркой в яме на дне кратера, свежие чёрные потоки лавы, которые стекают по настоящему рельефу (идут по ложбинам и долинам, на ровном растекаются, кончаются лопастями и могут запрудить долину), голый пепел на верхнем конусе, ниже — пояс обгоревших стоящих стволов; на кратере и свежих потоках не лежит снег. На конусе вулкана, в свежем кратере и у расщелины не строятся деревни, аванпосты и храмы.
- **Вулканические острова и подводные горы:** вулканы на морском дне поднимаются голыми вулканическими островами с пляжами (часть активна, с лавовым озером) или остаются под водой подводными горами из базальта и туфа со светящимися жерлами.
- **Расщелины:** пропасти длиной от нескольких сотен блоков до трёх километров и глубиной до 170, не ниже Y8. Их дно идёт по низкой земле вдоль линии, поэтому расщелина, пересекающая гору, разрезает её. Они пересекают и равнины, и горы; внутри сухо, к морю они не выходят; реки и стоки озёр падают в них водопадами в чашу на дне (на морозном севере — ледопадами).
- **Мир глубже и новые пещеры (только новые миры):** дно на Y−112. Уровни ходов, которые то есть, то нет, и наклоняются; губчатые полости, каньоны, колодцы, лабиринты и огромные залы-обрушения с десятками ходов из стен. Входы по-прежнему редкие (земля H ≥ 73).
- **Эскарпы:** полосы обрывов на краях плато и склонах гор, с них реки падают водопадами.
- **Большие водопады:** ручей, дошедший до крутого склона, срывается с его кромки одним водопадом на 12–40 блоков (на длинном склоне — двумя-тремя подряд) в чашу короткого ущелья, а не сбегает по склону перекатами. В замёрзших горах вся струя — ледопад.
- **Мелкие детали деревень** — фонари и клумбы — стоят на земле, а не висят над выровненным склоном.
- **Вода и формы рельефа согласованы:** нет стен морской воды у расщелин и в кратерах и кальдерах ниже уровня моря (замкнутая чаша сухая или со своим озером), нет рва вокруг вулкана, из стен расщелин не льются родники и лава, купола огромных залов сухие и без лавопадов по краю, ниже водопада нет поперечного обрыва на склоне долины.
- **Вулканические острова** получают биомы суши (лес, тайга, пляжи), в том числе для `/locate biome`; кораблекрушения, подводные руины и монументы остаются в воде вокруг них.

### В 0.9.3
- **Реки, озёра и водопады:** модель стока всего ландшафта (направления течения, водосборы, котловины озёр) решает, где течёт вода. Ручьи начинаются в настоящих истоках, сливаются в реки, которые расширяются вниз по течению и выходят к морю широкими долинами; озёра заполняют замкнутые котловины до уровня, проверенного на герметичность по блокам; водопады стоят на настоящих уступах. Модель считается при генерации чанков и не сохраняется: один и тот же сид всегда даёт ту же воду.
- **Форма климата:** пещеры получают климат поверхности над ними, климатические границы извиваются, а не идут прямыми линиями, маленький остров получает климат большей части своей суши.
- **Поиск:** `/locate biome minecraft:river` находит широкие реки (от 7 блоков) примерно в 2 км от игрока с любой высоты и указывает на саму реку.

### В 0.9.2
- **Генерация намного быстрее:** чанки создаются примерно в 4–14 раз быстрее. Выключенные ванильные карверы пещер больше не опрашивают климат ~289 соседних чанков на каждый чанк; блоки мира те же самые.
- **Снеговая линия по высоте:** горные биомы меняются по изолиниям высоты — снежные склоны примерно с Y168, пики с Y212 (в холодных регионах на 25 блоков ниже) при любой эрозии. Снежные склоны и пики больше не стоят рядом с лесом, лугом или вишнёвой рощей на той же высоте, а снежный ельник и снежные склоны — в низких долинах.
- **Тёплые горы:** каменистые пики примерно с Y157, выше — снежные вершины; лес выше скальной вершины не появляется.
- **Логичные морозные регионы:** морозная земля — снежная равнина, снежная тайга или снежный пляж до самой климатической границы: больше нет кольца дождливой тайги и дождливых каменистых берегов вокруг каждого морозного региона и зелёных дождливых ветреных холмов и лесов посреди снежной тайги.
- **Ледники:** ледниковые шапки на высоких холодных плато получают настоящую толщину (до ~18 блоков) под снегом; ледники лежат на настоящей поверхности, а не замуровывают снег и лёд внутрь горных склонов; ели на льду ледника не растут; снег возвращается туда, где убиралась трещина.
- **Океаны:** затонувшие корабли и подводные руины не стоят рядом с монументами; морская трава и kelp проверяют настоящую глубину воды рядом со структурами на дне; нет шва песок/глина между умеренно тёплым и тёплым океаном.
- **Пещеры:** горные пояса на поверхности больше не меняют биомы глубоких пещер (глубокая тьма, серные пещеры); в глубине тот же климат, что у нативной защиты водоносного слоя.
- **Экран отладки:** в F3 есть `FG lookup E` / `FG lookup W` — эрозия и странность, которые реально использует таблица биомов.

### В 0.9.1
- **Огромные снежные массивы:** большие холодные регионы поднимаются в середине хребтами из разных вершин; маленькие снежные регионы остаются холмами.
- **Горные биомы по реальной высоте** во всех горах: внизу лес и луга, выше снежные склоны и ельник, на вершинах ледяные и острые пики.
- **Логичные холодные переходы:** леса и равнины переходят к снежным равнинам через пояс тайги; холодные берега — снежные пляжи; очень влажная морозная земля — снежная тайга, а не дождливая; на прохладных высоких плато у снега растёт горный лес, а не саванна или джунгли.
- **Новые океаны:** абиссальные котловины, желоба, срединные хребты и подводные горы; базальт на вулканических поднятиях, глина в котловинах, осадки слоем в несколько блоков; морская трава, kelp и рифы зависят от света и грунта; подводных структур меньше, монументы стоят на нативных сваях.
- **Берега и гигантские горы:** нет стен по 80 блоков из моря, склоны гигантов мягче, на почти отвесных стенах голая скала вместо почвы.
- **Пещеры:** воронки и винтовые колодцы стоят на настоящих пересечениях двух галерей, размеры заданы в блоках; длинных разломов и колодцев, рассыпанных на замкнутые карманы, больше нет.
- **Деревни:** плавные берега у воды.

### Как устроен генератор

Всё построено на собственных функциях плотности, шумах и правилах материалов Minecraft. При сборке они компилируются в датапак, а рантайм-хуки отвечают за леса, ледники, модель стока рек и озёр (она решает, где стоит вода при генерации чанков; дальше работают обычные правила жидкостей игры) и дороги между деревнями. Новых биомов и блоков нет: выбор биомов, структуры, водоносные слои и жидкости остаются нативными.

**Рельеф.** Нативный трёхмерный шум поверхности заменён единой непрерывной оболочкой высоты, поэтому нет висящих островов и нависающих плит. Поверх неё:
- региональные формы: массивы, хребты, плато с широкими предгорьями и редкие провинции гигантских гор, которые поднимаются широкими склонами, а не отвесными стенами;
- огромные снежные массивы в ядрах больших холодных регионов: чем глубже внутри холодной зоны, тем выше горы (маленький снежный регион получает холмы, большой — хребет из разных вершин), с поясами по высоте: снежная равнина и тайга, снежные склоны и ельник, ледяные и острые пики; такие пояса есть во всех горах, поэтому пики больше не стоят рядом с лесом на той же высоте;
- побережья поднимаются вглубь суши под береговым пределом: на прочной породе остаются крутые морские утёсы, мягкие берега пологие, стен по 80 блоков прямо из моря нет;
- спокойные эродированные низины;
- плавный потолок высоты вместо ровного среза.

Добавочный рельеф не топит сухую землю ниже уровня моря, поэтому нет полей луж с травяным дном. На почти отвесных стенах почва не держится: в биомах с травяным покровом такие склоны становятся голой скалой.

**Рельеф по биомам.** Там, где климат выбирает биом, определяемый формой рельефа, высота строится по тем же климатическим полям:
- бедленды — ступенчатые столовые горы с уступами +24/+50/+80 блоков, каньонами и слоистыми терракотовыми обрывами;
- ветреные холмы — настоящие холмы до +44 блоков.

**Климат.** Области температуры и влажности широкие, поэтому биом занимает настоящий регион, а не россыпь пятен. Плавный изгиб границ делает их естественными. С высотой становится холоднее.
- **Экологические ниши:** тропический лес переходит в пустыню только через узкий пояс саванны и сухого леса; болота и мангры остаются у воды; на прохладных высоких плато у снеговой линии растёт горный лес, а не саванна или джунгли; леса и равнины переходят к снежным равнинам через пояс тайги.
- **Крупные и мелкие биомы:** крупные климатические биомы (равнины, леса, тайга, пустыни, океаны, снежные равнины) образуют большие регионы; особые (грибные острова, вишнёвые рощи, бледный сад, ледяные шипы) остаются маленькими и редкими. Климатические зоны очень крупные, поэтому жаркие пустыни и бедленды могут быть в нескольких километрах от спавна — это сделано намеренно, как в реальной географии.

**Пещеры — карстовая система** по образцу реальных известняковых пещер.
- **Три яруса** на уровнях бывших грунтовых вод: верхний около Y6, главный около Y−24, нижний около Y−47.
- **Галереи** идут по двум общим шумам-«планам трещин», поэтому ходы непрерывны и пересекаются. Главные галереи шириной 14–18 блоков переходят в залы 40–60 блоков с куполом и каменными колоннами во всю высоту.
- **Переходы вниз:** колоколообразные провалы на ходах и редкие огромные шахты через все ярусы.
- **Главный ярус:** каньоны «замочная скважина» под полом. **Нижний ярус:** лабиринтные участки и нативные лавовые озёра у самого дна.
- **Редкие входы (около 0,2–0,5 на км²)**, только на сухой земле выше Y73, поэтому вода у моря не встречается с открытой пещерой. Три вида:
  - круглые провальные воронки над пересечением двух галерей;
  - наклонные ходы, начинающиеся открытым ущельем;
  - колодцы на высоких плато, которые вьются вниз, как настоящие глубокие шахты: ствол медленно и неровно закручивается, поэтому короткие отвесы чередуются с уступами вместо одного прямого падения.
- В остальных местах над пещерами не меньше 14 блоков породы. Нативные карверы отключены: это защищает от пещер, выходящих на поверхность, и от зависшей воды.

**Леса.** Нативные деревья сохраняются, но кандидаты прореживаются и меняют размер по экологии:
- влажность, склон, открытость, граница леса и опушки;
- плавные переходы между типами лесов, включая редение к безлесным соседям.

Крона может сужаться, но не шире нативной, поэтому сгенерированная листва не опадает. Нативные проверки грунта, места и выживания сохраняются.

**Океаны.** Шельф остаётся спокойной равниной с осадками; глубокое дно делится на абиссальные котловины с редкими желобами, сети срединных хребтов с рифтом и одиночные округлые подводные горы разной высоты, поднимающиеся к свету. Хребты и подводные горы — молодая вулканическая порода: на их стенах и вершинах выходит гладкий базальт. В остальных местах на подводных отвесах выходит скала, на склонах — гравий, на ровном дне — песок (тёплые моря), гравий (холодные) и глина, которая собирается в глубоких котловинах; осадки лежат слоем в несколько блоков. Морская трава, kelp и тёплые рифы редеют с глубиной; kelp растёт пятнами-лесами на твёрдом грунте, рифы предпочитают скалу. Затонувших кораблей, руин и монументов меньше; монумент стоит на нативных сваях до самого дна.

**Ледники и айсберги.**
- Горный лёд заполняет холодные защищённые долины, с моренами и трещинами.
- Айсберги следуют непрерывной кривой температуры, без среза на границе океанов.

**Деревни.** Участки плавно вписываются в рельеф, без насыпных дамб над водой; приподнятый участок у воды получает короткий естественный берег вместо отвесного уступа.

### Сборка из исходников

Java 25, Gradle 9.8.2:

```bash
gradle build
```

Если зависимость не скачивается, добавьте `--init-script scripts/central-fallback.init.gradle` (тот же запасной источник, что в CI). JAR появляется в `build/libs`. Сборка компилирует датапак генерации (`src/tool`) и запускает все Java-тесты; опубликованные JAR в `dist/` — образцы для этих тестов. В `src/audit` и `src/smoke` — тестовые наблюдатели для проверок на сервере.

---

License: see [`LICENSE`](LICENSE) and [`NOTICE.md`](NOTICE.md).
