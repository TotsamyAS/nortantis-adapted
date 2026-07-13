# Очистка `nortantis-adapted` под REST-микросервис

> Анализ сделан по архиву `nortantis-adapted.zip`. Номера строк ниже относятся к исходному состоянию архива и после первых правок сдвинутся.

## Итог в двух абзацах

`editor.js` использует только 12 REST-вызовов редактора и загрузку локализации. В самом `editor.js` удаляемых функций не найдено: все 72 объявленные функции достижимы из обработчиков событий, сообщений или других функций; проверка TypeScript также не выявила неиспользуемых локальных переменных/параметров.

В Java есть безопасный первый слой очистки: 11 неиспользуемых HTTP-маршрутов, их обработчики и вспомогательные методы, 49 полностью недостижимых исходных классов, 9 картинок старого Swing-интерфейса и несколько явно мёртвых методов. Два ранее считавшихся legacy-маршрута — `/api/projects/default` и `/api/projects/export` — нужно сохранить для основного приложения. Далее можно убрать headless-сервису зависимости от Swing/FlatLaf и, отдельным необязательным этапом, вырезать heightmap/submap/desktop-editing функции.

---

## Как определялось использование

Точками входа считались:

- `src/nortantis/rest/editor/editor.js`;
- `src/nortantis/rest/NortantisRestServer.java` (`main` и зарегистрированные обработчики);
- неявные Java-механизмы, которые нельзя безопасно оценивать простым `grep`: виртуальные вызовы, лямбды (`invokedynamic`), статические инициализаторы, сериализуемые модели проекта.

Использовались:

1. AST-анализ JavaScript/TypeScript;
2. граф ссылок Java-исходников;
3. `jdeps` по уже собранным `.class` из архива;
4. консервативный bytecode-граф вызовов с учётом переопределений и лямбд;
5. поиск reflection/`ServiceLoader`/динамической загрузки классов — таких скрытых загрузок для удаляемых классов не найдено.

Ограничение проверки: чистый `./gradlew clean test` в среде анализа не выполнился, потому что wrapper пытается скачать Gradle 9.1.0, а сеть/DNS недоступны. В архиве были свежие `.class`, поэтому граф зависимостей проверен по байткоду; после удаления 49 классов `jdeps --missing-deps` не показал оставшихся внутренних ссылок на них. Финальную компиляцию и smoke-тесты всё равно нужно выполнить в вашей среде.

---

# Важная корректировка после анализа внешнего приложения

Дополнительно проверены загруженные SvelteKit route-модули основного приложения.

Подтверждено:

- `POST /nortantis/projects` вызывает `createNortantisProject(...)` и передаёт параметры `size` и `blank`;
- `POST /nortantis/projects/[id]/export` вызывает `exportNortantisProject(...)` и поддерживает форматы `jpg` и `png`;
- операции открытия, сохранения, preview, списка, переименования и удаления проекта работают через функции хранилища/проекта и сами по себе не требуют legacy edit/assets API Java-сервиса;
- editor продолжает работать через `/api/editor/session/*` и `/api/editor/assets/*`.

По контрактам это означает:

1. **оставить `/api/projects/default` и `defaultProject(HttpExchange)`** — они соответствуют созданию исходного `.nort` с параметрами `size`/`blank`;
2. **оставить `/api/projects/export` и `exportProject(HttpExchange)`** — это единственный legacy export endpoint, поддерживающий оба формата `jpg/png` и operation-id;
3. `/api/projects/export-stream` по-прежнему можно удалить: он жёстко формирует PNG и не соответствует сигнатуре внешнего export-маршрута;
4. остальные `/api/projects/edit|metadata|region-color|text-pick|brush-selection` и `/api/assets/*` не требуются показанными route-модулями.

Ограничение: в присланном архиве находятся route handlers, импортирующие `$lib/server/nortantis/projects`, но не сам файл реализации этого модуля. Поэтому соответствие `createNortantisProject → /api/projects/default` и `exportNortantisProject → /api/projects/export` основано на совпадении контрактов и имеет высокую, но не абсолютную уверенность. До появления реализации модуля эти два endpoint-кластера нельзя удалять или упрощать.

---

# Этап 1 — безопасное удаление

Делайте отдельным коммитом. Этот этап не меняет контракт, который реально использует `editor.js`.

## 1. Оставить маршруты редактора и основного приложения

В `NortantisRestServer.main` оставить:

```text
/editor
/health
/api/editor/session/open-stream
/api/editor/session/edit
/api/editor/session/project
/api/editor/session/generate-stream
/api/editor/session/region-color
/api/editor/session/brush-selection
/api/editor/session/text-pick
/api/editor/session/history
/api/editor/session/path-preview
/api/editor/session/topology
/api/editor/assets/icons
/api/editor/assets/icon-preview
/api/projects/default
/api/projects/export
```

`/health` не вызывается `editor.js`, но нужен для эксплуатации.

`/api/projects/default` сохранить для `createNortantisProject(...)`.

`/api/projects/export` сохранить для `exportNortantisProject(..., format)`.

## 2. Удалить 11 неиспользуемых регистраций маршрутов

В `src/nortantis/rest/NortantisRestServer.java`, исходные строки 107–133, удалить:

```java
server.createContext("/api/editor/session/open", NortantisRestServer::editorSessionOpen);
server.createContext("/api/editor/session/render", NortantisRestServer::editorSessionRender);
server.createContext("/api/editor/session/export", NortantisRestServer::editorSessionExport);
server.createContext("/api/projects/metadata", NortantisRestServer::projectMetadata);
server.createContext("/api/projects/export-stream", NortantisRestServer::exportProjectStream);
server.createContext("/api/projects/edit", NortantisRestServer::editProject);
server.createContext("/api/projects/region-color", NortantisRestServer::regionColor);
server.createContext("/api/projects/text-pick", NortantisRestServer::textPick);
server.createContext("/api/projects/brush-selection", NortantisRestServer::brushSelection);
server.createContext("/api/assets/icons", NortantisRestServer::iconAssets);
server.createContext("/api/assets/icon-preview", NortantisRestServer::iconPreview);
```

**Не удалять регистрации:**

```java
server.createContext("/api/projects/default", NortantisRestServer::defaultProject);
server.createContext("/api/projects/export", NortantisRestServer::exportProject);
```

Не удаляйте сами `editProject`, `regionColor`, `textPick`, `brushSelection`, `iconAssets`, `iconPreview`: они остаются обработчиками `/api/editor/...`. Удаляются только их legacy aliases.

## 3. Удалить обработчики, у которых больше не будет маршрутов

Из `NortantisRestServer.java` удалить целиком:

| Метод | Исходные строки | Причина |
|---|---:|---|
| `editorSessionOpen(HttpExchange)` | 186–235 | редактор использует `open-stream` |
| `editorSessionRender(HttpExchange)` | 356–379 | не вызывается editor или внешними route-модулями |
| `editorSessionExport(HttpExchange)` | 511–547 | editor сохраняет через `session/project`, внешний экспорт идёт через `/api/projects/export` |
| `editorHtml()` | 639–744 | старая встроенная HTML-страница |
| `projectMetadata(HttpExchange)` | 994–1019 | не используется показанными клиентами |
| `exportProjectStream(HttpExchange)` | 1021–1078 | формат только PNG; внешний export-контракт поддерживает JPG/PNG через обычный export |

**Не удалять:**

```text
defaultProject(HttpExchange)
exportProject(HttpExchange)
```

После удаления `projectMetadata` можно удалить:

```text
metadataJson(MapSettings)
metadataJson(EditorSession)
```

После удаления `exportProjectStream` можно удалить:

```text
EXPORT_STREAM_LOCK
PhaseForwardingOutputStream
java.io.PrintStream import, если больше нет ссылок
```

**Обязательно оставить, потому что они нужны `exportProject`:**

```text
readMapTitle(HttpExchange)
readExportFormat(HttpExchange)
readSessionId(HttpExchange)
readMaxDimensions(HttpExchange)
ExportFormat
```

Перегрузку `readMaxDimensions(JSONObject)` также оставить: она используется editor endpoint-ами.

## 4. Удалить полностью мёртвые private-методы сервера

Эти методы не имеют вызовов даже до удаления legacy-endpoint’ов:

```text
findNeighborRegionId(...)                 // 2666–2681
readLineRiPoints(...)                     // 2705–2725
findEdgesNearLine(...)                    // 3364–3392
centersOnOneSideOfBoundaryLine(...)       // 3418–3447
findSplitSides(...)                       // 3859–3885
splitSideSizes(...)                       // 3887–3895
```

## 5. Удалить два мёртвых метода вне REST-сервера

- `src/nortantis/TextDrawer.java`: `extractLocationsFromCorners(Collection<Corner>)` (исходные строки 567–576).
- `src/nortantis/util/HistogramEqualizer.java`: `writeToCSV(int[], String)` (исходные строки 165–179).

## 6. Удалить 49 недостижимых классов

Полный machine-readable список лежит рядом в `NORTANTIS_PHASE1_DELETE_MANIFEST.txt`.

### Не-Swing классы

```text
src/nortantis/SubMapCreator.java
src/nortantis/editor/EdgeType.java
src/nortantis/editor/MapChange.java
src/nortantis/editor/MapUpdater.java
src/nortantis/editor/NameType.java
src/nortantis/util/VisibleForTesting.java
```

### Старый Swing UI

Удалить все перечисленные файлы:

```text
src/nortantis/swing/AboutDialog.java
src/nortantis/swing/BGColorCancelHandler.java
src/nortantis/swing/BGColorPreviewPanel.java
src/nortantis/swing/BooksWidget.java
src/nortantis/swing/CollapsiblePanel.java
src/nortantis/swing/ControlClickBehaviorWidget.java
src/nortantis/swing/CustomImagesDialog.java
src/nortantis/swing/DrawModeWidget.java
src/nortantis/swing/EditClipboardButtonsWidget.java
src/nortantis/swing/EditorTool.java
src/nortantis/swing/FontChooser.java
src/nortantis/swing/GridBagOrganizer.java
src/nortantis/swing/HighlightMode.java
src/nortantis/swing/IconTypeButtons.java
src/nortantis/swing/IconsTool.java
src/nortantis/swing/ImageExportDialog.java
src/nortantis/swing/ImageExportType.java
src/nortantis/swing/ImagePanel.java
src/nortantis/swing/JComboBoxFixed.java
src/nortantis/swing/JFontChooser.java
src/nortantis/swing/LandWaterTool.java
src/nortantis/swing/MainWindow.java
src/nortantis/swing/MapEditingPanel.java
src/nortantis/swing/NameGeneratorDialog.java
src/nortantis/swing/NamedIconSelector.java
src/nortantis/swing/NewSettingsDialog.java
src/nortantis/swing/OverlayTool.java
src/nortantis/swing/RadioButtonWithImage.java
src/nortantis/swing/RowHider.java
src/nortantis/swing/SegmentedButtonWidget.java
src/nortantis/swing/SliderWithDisplayedValue.java
src/nortantis/swing/SliderWithSpinner.java
src/nortantis/swing/SubMapDialog.java
src/nortantis/swing/TextSearchDialog.java
src/nortantis/swing/TextTool.java
src/nortantis/swing/ThemePanel.java
src/nortantis/swing/ToolsPanel.java
src/nortantis/swing/Undoer.java
src/nortantis/swing/UnscaledImagePanel.java
src/nortantis/swing/UnscaledImageToggleButton.java
src/nortantis/swing/UpdateType.java
src/nortantis/swing/VerticallyScrollablePanel.java
src/nortantis/swing/WrapLayout.java
```

### Перед удалением `VisibleForTesting.java`

Удалить три аннотации и один import:

```text
src/nortantis/NameCompiler.java:166                         @VisibleForTesting
src/nortantis/graph/voronoi/VoronoiGraph.java:15            import nortantis.util.VisibleForTesting;
src/nortantis/graph/voronoi/VoronoiGraph.java:241           @VisibleForTesting
src/nortantis/graph/voronoi/VoronoiGraph.java:291           @VisibleForTesting
```

Менять visibility методов из-за удаления аннотации не требуется: аннотация не влияет на байткод/доступ.

## 7. Удалить 9 картинок старого Swing UI

```text
assets/internal/Icon tool.png
assets/internal/Land Water tool.png
assets/internal/Overlay tool.png
assets/internal/Text tool.png
assets/internal/move text.png
assets/internal/rotate text.png
assets/internal/scale.png
assets/internal/taskbar icon medium size.png
assets/internal/taskbar icon.png
```

Не удалять на первом этапе:

```text
assets/internal/old_paper.properties
assets/internal/en_GB.dic
assets/internal/mountain texture.png
```

`mountain texture.png` можно убрать позже, только одновременно с heightmap-кодом из необязательного этапа 4.

## 8. Обновить тесты после удаления классов

Обязательно:

1. Удалить `test/nortantis/SubMapCreatorTest.java` целиком.
2. В `test/nortantis/MapCreatorTest.java` удалить:
   - тест `drawWithoutEditsMatchesWithEdits` и helper `createMapUsingUpdater(...)`, использующие `MapUpdater`;
   - все тесты/импорты, использующие `SubMapCreator` или `SubMapDialog` (первый такой блок начинается примерно со строки 850).
3. Выполнить поиск по всему `test/`:

```bash
rg -n 'SubMapCreator|SubMapDialog|MapUpdater|VisibleForTesting' test src
```

Ссылок на удалённые символы остаться не должно.

---

# Этап 2 — убрать legacy-ветки внутри используемых обработчиков

Этот этап тоже соответствует текущему `editor.js`, но требует более внимательного редактирования методов. Делайте отдельным коммитом после успешного smoke-теста этапа 1.

## 1. `iconAssets` (исходные строки 1164–1220)

После удаления `/api/assets/icons` останется только JSON/session-вариант `/api/editor/assets/icons`.

Удалить:

- создание временного `.nort`-файла;
- ветку, воспринимающую request body как бинарный проект;
- fallback, создающий/генерирующий настройки без editor-session;
- cleanup временного файла.

Оставить: чтение JSON, обязательный `sessionId`, получение `EditorSession`, построение списка ассетов по настройкам сессии.

## 2. `iconPreview` (1222–1301)

После удаления `/api/assets/icon-preview` оставить только JSON/session-ветку.

Удалить:

- header-based/raw-project ветки;
- временный проект;
- fallback без сессии.

После упрощения удалить `requiredHeader(HttpExchange, String)` (1303–1311), если больше нет ссылок.

## 3. `regionColor` (1377–1442)

`editor.js` всегда отправляет существующий `sessionId`. Удалить fallback с `projectBase64` и временным файлом (примерно 1411–1428). При отсутствующей сессии возвращать понятную ошибку 400/404.

## 4. `textPick` (1444–1503)

Аналогично: удалить fallback с `projectBase64`, временным файлом и созданием синтетической сессии (примерно 1472–1489).

## 5. `editProject` (1080–1162)

Текущий фронтенд отправляет:

```json
{
  "sessionId": "...",
  "command": { "type": "..." },
  "returnPreview": true,
  "omitProjectBytes": true
}
```

Поэтому можно удалить:

- `projectBase64`-ветку;
- создание временного проекта;
- non-session fallback;
- ветку полного `applyEditCommand(...)`, если после упрощения не осталось других вызовов.

Все команды, которые реально формирует текущий `editor.js` (terrain, region paint/lasso/boundary, road/river draw/erase, text CRUD, icon/trees), проходят через incremental/session обработку. Но удаление `applyEditCommand` делайте только после повторного `rg` и компиляции.

## 6. CORS

После удаления header-based legacy API в `addCorsHeaders` достаточно `Content-Type`, если ваш gateway не добавляет собственные обязательные headers. Сначала проверьте deployment-конфигурацию.

---

# Этап 3 — headless-рефакторинг и зависимости

Некоторые классы формально достижимы не потому, что нужны микросервису, а из-за старых связей между core и Swing.

## Критически важное исключение

**Не удалять `src/nortantis/swing/MapEdits.java`.** Несмотря на пакет `swing`, это часть состояния/формата `.nort` и core-редактирования.

## 1. Отвязать локализацию от `UserPreferences`

В `src/nortantis/swing/translation/Translation.java` убрать зависимость от `nortantis.editor.UserPreferences`.

Для сервиса язык лучше брать в таком порядке:

1. `NORTANTIS_LOCALE`;
2. system property `nortantis.locale`;
3. `Locale.getDefault()`;
4. безопасный fallback, например `ru` или `en`, согласно контракту сервиса.

После этого можно удалить:

```text
src/nortantis/editor/UserPreferences.java
src/nortantis/swing/LookAndFeel.java
```

## 2. Упростить `Logger`

После удаления `MainWindow` у `Logger.setLoggerTarget(...)` нет runtime-потребителей. Перевести logger на прямой stdout/stderr и удалить target-механику:

```text
setLoggerTarget
clear
clearTarget
appendToTarget
поле/синглтон logger target
```

Затем удалить:

```text
src/nortantis/util/ILoggerTarget.java
```

## 3. Упростить platform abstraction

После удаления `MapUpdater`/desktop UI убрать неиспользуемый `doInBackgroundThread(BackgroundTask<T>)` из `PlatformFactory` и реализации `AwtFactory`, затем удалить:

```text
src/nortantis/platform/BackgroundTask.java
```

Убрать из `AwtFactory` Swing imports и вызовы `SwingHelper`, после чего можно удалить:

```text
src/nortantis/swing/SwingHelper.java
src/nortantis/swing/DynamicLineBorder.java
```

`AwtFactory` как таковой оставьте: генерация карт использует AWT-реализацию изображений, шрифтов и графики даже в headless-режиме.

## 4. Упростить `OSHelper`

Метод `openFileExplorerTo(File)` нужен только desktop UI. Удалить его и ставшие лишними AWT/Swing/File/IOException imports. Оставить определение ОС и `getAppDataPath`, так как они используются core-кодом и asset/settings-логикой.

## 5. `build.gradle.kts`

Изменить:

```kotlin
application {
    mainClass.set("nortantis.rest.NortantisRestServer")
    applicationDefaultJvmArgs = listOf(
        "--enable-native-access=ALL-UNNAMED",
        "-Dfile.encoding=UTF-8",
        "-Djava.awt.headless=true",
        "-Dsun.java2d.d3d=false",
        "-Xmx3g",
    )
}
```

В `tasks.jar.manifest` заменить:

```kotlin
attributes("Main-Class" to "nortantis.rest.NortantisRestServer")
```

После удаления UI удалить dependency:

```kotlin
implementation("com.formdev:flatlaf:3.6.2")
```

Остальные production dependencies пока оставить: анализ байткода показал runtime-ссылки на Commons IO, Commons Lang, Commons Math, imgscalr, json-simple и JTransforms.

`runRest` после смены application main становится дублирующим. Его можно удалить или оставить как удобный alias.

---

# Этап 4 — необязательная глубокая обрезка функций

Это не нужно для первого безопасного результата. Удаляйте только если сервис точно не предоставляет эти возможности через другой клиент/API.

## 1. Heightmap

Удалить вместе:

```text
GraphCreator.createHeightMap(...)
GraphCreator.subtractTextureFromHeightMapUsingSeaLevel(...)
MapCreator.createHeightMap(...)
assets/internal/mountain texture.png
```

Также удалить/изменить heightmap-проверку в `test/nortantis/MapTestUtil.java` (в исходнике вызов около строки 213).

## 2. Остатки submap/tree replanting

После удаления `SubMapCreator` можно проверить и удалить недостижимые методы `IconDrawer`:

```text
rebuildAnchoredTrees(...)
getMostCommonTreeType(...)
getRepresentativeTreeColors(...)
hasVisibleTreeWithinDistance(...)
iconTypeToCenterIconType(...)
```

После этого может стать ненужным:

```text
src/nortantis/util/Tuple2Comp.java
```

Удалять его только после `rg`/компиляции.

## 3. Desktop-only river editing

REST-сервер содержит собственную incremental-логику рек. Следующий публичный desktop API не достигается от сервиса:

```text
RiverDrawer.addRiversFromEdgesInEditor(...)
RiverDrawer.updateExistingRiverWidthsForEdges(...)
RiverDrawer.addFreeHandRiverFromPoints(...)
RiverDrawer.updateExistingRiverWidthsForPointPairs(...)
RiverDrawer.findRiverSegmentsToRemoveForWaterPaint(...)
RiverDrawer.removeSegmentsAndSplitRivers(...)
RiverDrawer.mergeAdjacentRivers(...)
```

Вместе с ними удаляется их private helper-chain. Затем убрать соответствующие тесты из `RiverDrawerTest`.

## 4. Desktop-only road editing

Аналогично кандидаты:

```text
RoadDrawer.addRoadsFromEdgesInEditor(...)
RoadDrawer.mergeAdjacentRoads(...)
RoadDrawer.addFreeHandRoadFromPoints(...)
RoadDrawer.removeSegmentsAndSplitRoads(...)
```

И связанные private helpers, включая `roadNodesList(...)`. Затем убрать соответствующие тесты из `RoadDrawerTest`.

## 5. Другие подтверждённые feature-кластеры

После этапов выше повторно проверить и при отсутствии ссылок удалить:

- greedy path API в `WorldGraph`: обе перегрузки `findPathGreedy`, `expandFrontier`, `CornerSearchNode` и связанный back-pointer helper;
- `ImageHelper.copyAlphaTo(...)` и `copyAlphaToCPU(...)` (desktop map editing);
- `Stopwatch.java`, если удалены benchmark/demo `main` в `BackgroundGenerator` и `FractalBGGenerator`;
- submap-поля/методы в `DebugFlags`.

Не применяйте автоматическое правило «private method без прямого textual call = удалить» ко всему проекту: enum/serialization/callback/overload/virtual dispatch дают ложные срабатывания.

---

# Ресурсы: что оставить

## Обязательно оставить

- `assets/internal/old_paper.properties` — используется генератором настроек/темы.
- `assets/internal/en_GB.dic` — используется `NameCompiler`.
- `assets/installed art pack/**` — генерация и открываемые `.nort` могут ссылаться на backgrounds, borders, mountains, hills, sand, trees, cities и decorations. Набор иконок, показанный текущим UI, не покрывает все runtime-ссылки.
- `src/nortantis/rest/editor/index.html`, `editor.css`, `editor.js`, `i18n/ru.json`.

## `assets/books`

Не удалять целиком без изменения контракта генерации. Русская генерация использует как минимум:

```text
ru_fantasy_person_names.txt
ru_fantasy_place_names.txt
ru_fantasy_region_names.txt
ru_fantasy_region_disallowed_suffixes.txt
```

Если сервис гарантированно только русскоязычный и не открывает английские/старые проекты, можно оставить только эти четыре файла и поправить `SettingsGenerator`, чтобы он не выбирал английские books. Иначе сохранить весь каталог.

## Тестовые и generated-артефакты

Не нужны в production image/jar:

```text
bin/
build/
unit test files/
libraries-doc/
library-sources/
installers/
```

`unit test files/` можно оставить в git, если запускаете visual regression, но не копировать в production image.

Если Eclipse не используется, удалить:

```text
.classpath
.project
```

Для service-only репозитория можно удалить/заменить desktop packaging workflows в `.github` и installer scripts.

---

# Что нельзя удалять, хотя название выглядит подозрительно

1. `src/nortantis/swing/MapEdits.java` — core-состояние проекта.
2. `AwtFactory` и AWT image/font classes — нужны генерации изображений в headless JVM.
3. DTO/поля `MapSettings`, даже когда отдельное поле не читается явно в REST-файле: они участвуют в совместимости `.nort`, JSON/serialization и глубоком core-коде.
4. `readMaxDimensions(JSONObject)`.
5. `SseEmitter` — используется `open-stream` и `generate-stream`.
6. `ToolLog.PROJECTS`, `SAVE_EXPORT`, `ASSETS`, `GENERATION` — используются текущими endpoint’ами.
7. `Base64` — используется текущим протоколом editor/server.
8. production dependencies, кроме FlatLaf после удаления UI, до успешной clean-сборки.

---

# Рекомендуемый порядок коммитов

## Коммит A — REST surface

- удалить 11 route registrations;
- сохранить `/api/projects/default` и `/api/projects/export`;
- удалить 6 обработчиков без потребителей;
- удалить только helpers `projectMetadata` и `exportProjectStream`;
- сохранить `ExportFormat` и export-header helpers;
- удалить 6 мёртвых private-методов сервера;
- удалить два мёртвых метода в `TextDrawer`/`HistogramEqualizer`.

Проверка: server стартует, `/health` отвечает, редактор открывается и выполняет основные операции.

## Коммит B — 49 классов и UI-ресурсы

- убрать аннотации `VisibleForTesting`;
- удалить 49 классов;
- удалить 9 PNG;
- почистить тесты и imports.

## Коммит C — legacy branches/headless

- упростить `iconAssets`, `iconPreview`, `regionColor`, `textPick`, `editProject`;
- отвязать Translation/Logger/PlatformFactory/OSHelper от Swing;
- убрать FlatLaf;
- сменить main class.

## Коммит D — optional feature pruning

- heightmap;
- tree/submap helpers;
- desktop road/river APIs;
- дополнительные недостижимые helpers/resources.

Так `git bisect` быстро покажет слой, на котором возникла регрессия.

---

# Проверки после каждого этапа

## Статические

```bash
# Удалённые символы не должны остаться в исходниках
rg -n 'SubMapCreator|SubMapDialog|MapUpdater|VisibleForTesting|nortantis\.swing\.MainWindow' src test

# После headless-рефакторинга проверить Swing-ссылки вне MapEdits/translation
rg -n '^import javax\.swing|javax\.swing\.' src/nortantis

# Проверка внутренних отсутствующих class dependencies после сборки
jdeps --recursive --missing-deps build/classes/java/main
```

## Сборка

```bash
./gradlew clean test
./gradlew jar
java -jar build/libs/Nortantis.jar
```

## HTTP smoke test

```bash
curl -fsS http://localhost:8091/health
```

Затем проверить интеграцию основного приложения:

- создание проекта (`POST /nortantis/projects`) для `blank=true/false` и размеров 1024/2048/4096;
- экспорт существующего проекта в JPG и PNG;
- корректную передачу operation-id и обработку недоступности Java-сервиса.

Затем открыть `/editor` и проверить:

- открытие проекта через streaming endpoint;
- генерацию новой карты;
- terrain editing;
- region paint/lasso/boundary;
- road и river draw/erase;
- text create/update/delete/pick;
- icon placement/erase и trees;
- undo/redo;
- сохранение проекта через `session/project` и передачу в parent через `postMessage`;
- повторное открытие сохранённого `.nort`.

---

# Критерий готовности

Очистку можно считать безопасной, когда одновременно выполнено:

1. `clean test` проходит;
2. `jdeps --missing-deps` не показывает внутренних отсутствующих классов;
3. все 12 editor API и SSE-потоки работают;
4. `/api/projects/default` создаёт валидный `.nort`;
5. `/api/projects/export` экспортирует JPG и PNG;
6. сохранённый проект повторно открывается без потери edits/assets/text;
7. production jar/image не содержит удалённые Swing-классы, installer/test artifacts и FlatLaf.