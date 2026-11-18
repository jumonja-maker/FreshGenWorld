**[⬇ Download the latest version / Скачать последнюю версию](https://github.com/jumonja-maker/FreshGenWorld/releases/latest)**

# FreshGenerator

A world-generation mod for **Minecraft Java 26.3** (Fabric). It reshapes terrain, climate, biome borders, forests, glaciers and caves toward natural, logical landscapes, while keeping native Minecraft biomes, structures, water and blocks.

**[English](#english) · [Русский](#русский)**

---

## English

### Install

1. Install **Fabric Loader 0.20.1+** and **Fabric API** for Minecraft **26.3**. Java **25** is required.
2. Download `FreshGenerator-Fabric-26.3-<version>.jar` from the [latest release](https://github.com/jumonja-maker/FreshGenWorld/releases/latest) and put it into the `mods` folder.
3. Create a new world with the normal **Default** world type: FreshGenerator replaces it, no extra option to pick.

Existing chunks are never rebuilt; new terrain appears only in new chunks. The mod is alpha: back up your worlds.

### How the generator works

Everything is built from Minecraft's own density functions, noise and material rules, compiled into a datapack at build time. A few small runtime hooks handle forests and glaciers. The mod adds no new biomes or blocks and no custom water simulation. Native biome selection, structures, aquifers and fluids stay in charge.

**Terrain.** One continuous height envelope replaces the native 3-D surface noise, so there are no floating islands or overhanging slabs. On top of that envelope:
- regional landforms: massifs, ridges, plateaus with broad foothill aprons, and rare giant mountain provinces;
- soft coasts, both gentle and rocky;
- quiet eroded lowlands;
- a smooth height ceiling instead of a flat cut.

Added relief never drowns dry land below sea level, so there are no fields of puddles with grass floors.

**Biome-defined relief.** Where the climate selects a landform biome, the terrain follows the same climate fields:
- badlands become stepped mesas, with benches of +24/+50/+80 blocks and canyons between them, and layered terracotta cliffs;
- windswept areas become real hills, up to +44 blocks.

**Climate.** Temperature and humidity regions are wide, so a biome covers a real region instead of small speckles. A smooth mid-scale bend keeps their borders natural. Temperature drops with altitude.
- **Ecological niches:** rainforest reaches desert only through a narrow belt of savanna and dry forest; swamps and mangroves stay near the water surface.
- **Large and small biomes:** large climate biomes (plains, forests, taiga, deserts, oceans, snowy plains) form large regions; special ones (mushroom fields, cherry groves, pale garden, ice spikes) stay small and rare.

**Caves: a karst system** modelled on real limestone caves.
- **Three stacked levels** like former water tables: upper ~Y 6, main ~Y −24, deep ~Y −47.
- **Galleries** follow two shared "joint plan" noises, so passages are continuous and meet at junctions. Main galleries are 14–18 blocks wide, opening into domed halls 40–60 wide with full-height stone pillars.
- **Transitions down** go through bell-shaped pits on the passages and rare great shafts through all levels.
- **Main level:** vadose "keyhole" canyons cut below its floor. **Deep level:** anastomotic maze zones and native lava lakes at the very bottom.
- **Rare entrances (~0.7 per km²)**, only on dry ground above Y 73, so sea-level water never meets an open cave. Three kinds:
  - collapse dolines that open onto a gallery;
  - inclined passages that start as a roofless gorge;
  - narrow potholes on high plateaus.
- Everywhere else at least 14 blocks of rock cover the caves. Native carvers are disabled; this prevents caves breaking the surface and leaving hanging water.

**Forests.** Native tree features stay, but candidates are thinned and resized by ecology:
- moisture, slope, exposure, treeline and forest edges;
- smooth ecotones between forest types, including thinning toward treeless neighbours.

Crowns may narrow but never grow past the native radius, so generated leaves do not decay. Trees keep their native soil, space and survival checks.

**Glaciers and icebergs.**
- Mountain ice fills cold, sheltered valleys, with moraines and crevasses.
- Icebergs follow a continuous temperature curve with no cut at ocean borders.

**Villages.** Village plots are graded smoothly into the terrain, without dams over water.

### Build from source

Java 25, Gradle 9.8.2:

```bash
gradle build
```

If a dependency cannot be resolved, add `--init-script scripts/central-fallback.init.gradle` (the same fallback CI uses). The JAR is written to `build/libs`. The build compiles the worldgen pack (`src/tool`) and runs all Java tests. The `scripts/` folder holds package validators and analysis tools. `src/audit` contains test-only server observers used for native generation audits.

Documentation for every subsystem is in [`docs/`](docs/). Start with [`docs/caves-0.9.0.md`](docs/caves-0.9.0.md) and [`docs/product-direction.md`](docs/product-direction.md).

---

## Русский

### Установка

1. Установите **Fabric Loader 0.20.1+** и **Fabric API** для Minecraft **26.3**. Нужна Java **25**.
2. Скачайте `FreshGenerator-Fabric-26.3-<версия>.jar` из [последнего релиза](https://github.com/jumonja-maker/FreshGenWorld/releases/latest) и положите в папку `mods`.
3. Создайте новый мир в обычном режиме **«По умолчанию»**: его создаёт FreshGenerator, отдельно выбирать ничего не нужно.

Существующие чанки не перестраиваются: новая генерация появляется только в новых чанках. Мод в стадии alpha, делайте резервные копии миров.

### Как устроен генератор

Всё построено на собственных функциях плотности, шумах и правилах материалов Minecraft. При сборке они компилируются в датапак, а небольшие рантайм-хуки отвечают за леса и ледники. Новых биомов, блоков и собственной симуляции воды нет: выбор биомов, структуры, водоносные слои и жидкости остаются нативными.

**Рельеф.** Нативный трёхмерный шум поверхности заменён единой непрерывной оболочкой высоты, поэтому нет висящих островов и нависающих плит. Поверх неё:
- региональные формы: массивы, хребты, плато с широкими предгорьями и редкие провинции гигантских гор;
- мягкие и скалистые побережья;
- спокойные эродированные низины;
- плавный потолок высоты вместо ровного среза.

Добавочный рельеф не топит сухую землю ниже уровня моря, поэтому нет полей луж с травяным дном.

**Рельеф по биомам.** Там, где климат выбирает биом, определяемый формой рельефа, высота строится по тем же климатическим полям:
- бедленды — ступенчатые столовые горы с уступами +24/+50/+80 блоков, каньонами и слоистыми терракотовыми обрывами;
- ветреные холмы — настоящие холмы до +44 блоков.

**Климат.** Области температуры и влажности широкие, поэтому биом занимает настоящий регион, а не россыпь пятен. Плавный изгиб границ делает их естественными. С высотой становится холоднее.
- **Экологические ниши:** тропический лес переходит в пустыню только через узкий пояс саванны и сухого леса; болота и мангры остаются у воды.
- **Крупные и мелкие биомы:** крупные климатические биомы (равнины, леса, тайга, пустыни, океаны, снежные равнины) образуют большие регионы; особые (грибные острова, вишнёвые рощи, бледный сад, ледяные шипы) остаются маленькими и редкими.

**Пещеры — карстовая система** по образцу реальных известняковых пещер.
- **Три яруса** на уровнях бывших грунтовых вод: верхний около Y6, главный около Y−24, нижний около Y−47.
- **Галереи** идут по двум общим шумам-«планам трещин», поэтому ходы непрерывны и пересекаются. Главные галереи шириной 14–18 блоков переходят в залы 40–60 блоков с куполом и каменными колоннами во всю высоту.
- **Переходы вниз:** колоколообразные провалы на ходах и редкие огромные шахты через все ярусы.
- **Главный ярус:** каньоны «замочная скважина» под полом. **Нижний ярус:** лабиринтные участки и нативные лавовые озёра у самого дна.
- **Редкие входы (около 0,7 на км²)**, только на сухой земле выше Y73, поэтому вода у моря не встречается с открытой пещерой. Три вида:
  - провальные воронки, которые открываются в галерею;
  - наклонные ходы, начинающиеся открытым ущельем;
  - узкие колодцы на высоких плато.
- В остальных местах над пещерами не меньше 14 блоков породы. Нативные карверы отключены: это защищает от пещер, выходящих на поверхность, и от зависшей воды.

**Леса.** Нативные деревья сохраняются, но кандидаты прореживаются и меняют размер по экологии:
- влажность, склон, открытость, граница леса и опушки;
- плавные переходы между типами лесов, включая редение к безлесным соседям.

Крона может сужаться, но не шире нативной, поэтому сгенерированная листва не опадает. Нативные проверки грунта, места и выживания сохраняются.

**Ледники и айсберги.**
- Горный лёд заполняет холодные защищённые долины, с моренами и трещинами.
- Айсберги следуют непрерывной кривой температуры, без среза на границе океанов.

**Деревни.** Участки плавно вписываются в рельеф, без насыпных дамб над водой.

### Сборка из исходников

Java 25, Gradle 9.8.2:

```bash
gradle build
```

Если зависимость не скачивается, добавьте `--init-script scripts/central-fallback.init.gradle` (тот же запасной источник, что в CI). JAR появляется в `build/libs`. Сборка компилирует датапак генерации (`src/tool`) и запускает все Java-тесты. В `scripts/` лежат валидаторы пакета и инструменты анализа. В `src/audit` — тестовые наблюдатели для нативных проверок генерации на сервере.

Документация по каждой подсистеме — в [`docs/`](docs/). Начните с [`docs/caves-0.9.0.md`](docs/caves-0.9.0.md) и [`docs/product-direction.md`](docs/product-direction.md).

---

License: see [`LICENSE`](LICENSE) and [`NOTICE.md`](NOTICE.md).
