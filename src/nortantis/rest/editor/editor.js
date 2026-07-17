(() => {
	'use strict';

	const FALLBACK_LOCALE = 'ru';
	const THEME_STORAGE_KEY = 'dungeon-overlord-theme';
	const EMPTY_LABELS = { phases: {}, textTypes: {}, statuses: {}, errors: {} };
	const REGION_COLORS = [
		'#8f3b3b', '#b85c4a', '#c47a3a', '#b9945d',
		'#d0a85c', '#8a8f4a', '#4f7a55', '#3e7a70',
		'#3f6f8f', '#4d5e91', '#65558f', '#80558a',
		'#99586e', '#7a5a48', '#6e6e62', '#a88b72'
	];
	const FALLBACK_TEXT_STYLES = {
		Title: { fontFamily: 'Georgia', size: 54, weight: 700, italic: false, boldBackground: true },
		Region: { fontFamily: 'Georgia', size: 38, weight: 700, italic: false, boldBackground: true },
		Mountain_range: { fontFamily: 'Georgia', size: 32, weight: 400, italic: true, boldBackground: false },
		Other_mountains: { fontFamily: 'Georgia', size: 24, weight: 400, italic: true, boldBackground: false },
		City: { fontFamily: 'Georgia', size: 22, weight: 700, italic: false, boldBackground: false },
		Lake: { fontFamily: 'Georgia', size: 22, weight: 400, italic: true, boldBackground: false },
		River: { fontFamily: 'Georgia', size: 22, weight: 400, italic: true, boldBackground: false }
	};
	const dictionaryCache = new Map();
	let localeRequestId = 0;

	const state = {
		labels: EMPTY_LABELS,
		locale: '',
		host: { projects: [], selectedProjectId: null, storage: null, loading: false, saving: false, exporting: false, statusCode: '', errorCode: '', sourceCodeURL: '', backUrl: '/hub' },
		sessionId: null,
		projectName: '',
		metadata: null,
		activeTool: 'projects',
		scale: 1,
		panX: 0,
		panY: 0,
		hasFit: false,
		space: false,
		panning: false,
		drawing: false,
		lastX: 0,
		lastY: 0,
		terrainMode: 'land',
		terrainRadius: 48,
		boundaryRadius: 36,
		boundaryPoints: [],
		provinceMode: 'paint',
		regionColor: '#b9945d',
		regionHue: 36,
		regionSaturation: 50,
		regionBrightness: 73,
		lineType: 'road',
		lineMode: 'draw',
		lineRadius: 24,
		lineWidth: 3,
		linePoints: [],
		pathPreviewPoints: [],
		pathPreviewPolygons: [],
		pathPreviewEdges: [],
		selectedText: null,
		draggingText: false,
		textDragPoint: null,
		iconMode: 'place',
		iconAssets: null,
		iconsLoading: false,
		selectedAsset: null,
		treeDensity: 5,
		treeRadius: 48,
		cursorPoint: null,
		brushPolygons: [],
		brushEdges: [],
		editPending: false,
		queuedEdit: null,
		queuedHistory: null,
		historyGroup: null,
		historyCounter: 0,
		dirty: false,
		generationBlank: false,
		previewVersion: 0,
		projectPreviewData: {},
		deleteProjectCandidate: null,
		renameProjectCandidate: null,
		lastHostStatus: '',
		loadingProgress: 0,
		exportPreparing: false,
		exportHostStarted: false
	};
	let brushSelectionTimer = 0;
	let brushSelectionVersion = 0;
	let brushSelectionPending = false;
	let queuedBrushSelection = null;
	let pathPreviewTimer = 0;
	let pathPreviewVersion = 0;
	let pathPreviewPending = false;
	let queuedPathPreview = null;
	let toastTimer = 0;
	let previewPatchQueue = Promise.resolve();

	const byId = (id) => document.getElementById(id);

	function normalizeTheme(value) {
		return value === 'light' ? 'light' : 'dark';
	}

	function readStoredTheme() {
		try {
			return normalizeTheme(localStorage.getItem(THEME_STORAGE_KEY));
		} catch {
			return normalizeTheme(document.documentElement.dataset.theme);
		}
	}

	function renderThemeToggle() {
		const toggle = byId('themeToggle');
		if (!toggle) return;
		const isLight = document.documentElement.dataset.theme === 'light';
		const actionLabel = label(isLight ? 'switchToDark' : 'switchToLight');
		toggle.classList.toggle('light-active', isLight);
		toggle.setAttribute('aria-pressed', String(isLight));
		toggle.setAttribute('aria-label', actionLabel);
		toggle.dataset.tooltip = actionLabel;
	}

	function applyTheme(theme, { persist = true } = {}) {
		const normalized = normalizeTheme(theme);
		document.documentElement.dataset.theme = normalized;
		if (persist) {
			try {
				localStorage.setItem(THEME_STORAGE_KEY, normalized);
			} catch {
				// Storage can be unavailable in private or restricted browsing contexts.
			}
		}
		renderThemeToggle();
	}

	function toggleTheme() {
		const theme = document.documentElement.dataset.theme === 'light' ? 'dark' : 'light';
		applyTheme(theme, { persist: false });
		postParent('nortantis:theme-change', { theme });
	}
	const canvas = byId('canvas');
	const mapSurface = byId('mapSurface');
	const map = byId('map');
	const overlay = byId('mapOverlay');
	const loading = byId('loading');
	const emptyWorkspace = byId('emptyWorkspace');
	const inlineText = byId('inlineText');

	function iconUse(id) {
		return `<svg aria-hidden="true"><use href="#icon-${id}"></use></svg>`;
	}

	function renderSideFire() {
		const fire = byId('editorSideFire');
		if (!fire || fire.childElementCount) return;

		const fragment = document.createDocumentFragment();
		Array.from({ length: 78 }, (_, index) => ({
			x: `${4 + ((index * 47) % 93)}%`,
			y: `${(index * 11) % 19}%`,
			size: `${2 + ((index * 5) % 6)}px`,
			rotation: `${-34 + ((index * 29) % 69)}deg`,
			duration: `${3.4 + ((index * 13) % 47) / 10}s`,
			delay: `${-((index * 17) % 83) / 10}s`,
			drift: `${-34 + ((index * 31) % 69)}px`,
			rise: `${150 + ((index * 37) % 361)}px`
		})).forEach((spark) => {
			const element = document.createElement('i');
			element.className = 'game-side-spark';
			element.style.setProperty('--spark-x', spark.x);
			element.style.setProperty('--spark-y', spark.y);
			element.style.setProperty('--spark-size', spark.size);
			element.style.setProperty('--spark-rotation', spark.rotation);
			element.style.setProperty('--spark-duration', spark.duration);
			element.style.setProperty('--spark-delay', spark.delay);
			element.style.setProperty('--spark-drift', spark.drift);
			element.style.setProperty('--spark-rise', spark.rise);
			fragment.append(element);
		});
		fire.append(fragment);
	}

	renderSideFire();

	function label(key, params = {}) {
		const value = typeof state.labels[key] === 'string' ? state.labels[key] : '';
		return Object.entries(params).reduce((text, [name, replacement]) => text.replaceAll(`{${name}}`, String(replacement)), value);
	}

	function setLabels(labels) {
		state.labels = {
			...EMPTY_LABELS,
			...(labels || {}),
			phases: { ...(labels?.phases || {}) },
			textTypes: { ...(labels?.textTypes || {}) },
			statuses: { ...(labels?.statuses || {}) },
			errors: { ...(labels?.errors || {}) }
		};
		document.title = label('documentTitle');
		document.querySelectorAll('[data-label]').forEach((element) => { element.textContent = label(element.dataset.label); });
		document.querySelectorAll('[data-label-placeholder]').forEach((element) => { element.placeholder = label(element.dataset.labelPlaceholder); });
		document.querySelectorAll('[data-label-aria]').forEach((element) => { element.setAttribute('aria-label', label(element.dataset.labelAria)); });
		const toolLabels = { projects: 'projects', generation: 'generation', terrain: 'terrain', provinces: 'provinces', lines: 'lines', text: 'text', icons: 'icons', about: 'about' };
		document.querySelectorAll('[data-tool-button]').forEach((button) => {
			const text = label(toolLabels[button.dataset.toolButton]);
			button.dataset.tooltip = text;
			button.setAttribute('aria-label', text);
		});
		byId('saveProject').dataset.tooltip = label('save');
		byId('saveProject').setAttribute('aria-label', label('save'));
		byId('exportProject').dataset.tooltip = label('export');
		byId('exportProject').setAttribute('aria-label', label('export'));
		byId('backLink').dataset.tooltip = label('back');
		byId('backLink').setAttribute('aria-label', label('back'));
		renderThemeToggle();
		byId('regionColorTrigger')?.setAttribute('aria-label', label('regionColor'));
		const createButton = byId('createProjectForm').querySelector('button');
		createButton.dataset.tooltip = label('create');
		createButton.setAttribute('aria-label', label('create'));
		if (state.deleteProjectCandidate) {
			byId('deleteProjectMessage').textContent = label('deleteProjectConfirmation', {
				name: state.deleteProjectCandidate.name
			});
		}
		if (state.renameProjectCandidate) {
			byId('renameProjectName').value = state.renameProjectCandidate.name;
		}
		fillTextTypes();
		renderRegionPalette();
		setRegionColor(state.regionColor);
		renderHostState();
	}

	function clampRegionColorValue(value, min, max) {
		return Math.min(max, Math.max(min, Number(value) || 0));
	}

	function normalizeRegionColor(color) {
		const raw = String(color || '').trim();
		const shortMatch = raw.match(/^#?([0-9a-f]{3})$/i);
		if (shortMatch) {
			const [r, g, b] = shortMatch[1];
			return `#${r}${r}${g}${g}${b}${b}`.toLowerCase();
		}
		const fullMatch = raw.match(/^#?([0-9a-f]{6})$/i);
		return fullMatch ? `#${fullMatch[1].toLowerCase()}` : null;
	}

	function regionHexToHsv(color) {
		const normalized = normalizeRegionColor(color) || '#000000';
		const r = Number.parseInt(normalized.slice(1, 3), 16) / 255;
		const g = Number.parseInt(normalized.slice(3, 5), 16) / 255;
		const b = Number.parseInt(normalized.slice(5, 7), 16) / 255;
		const max = Math.max(r, g, b);
		const min = Math.min(r, g, b);
		const delta = max - min;
		let hue = state.regionHue || 0;
		if (delta > 0) {
			if (max === r) hue = 60 * (((g - b) / delta) % 6);
			else if (max === g) hue = 60 * ((b - r) / delta + 2);
			else hue = 60 * ((r - g) / delta + 4);
		}
		if (hue < 0) hue += 360;
		return { h: hue, s: max === 0 ? 0 : (delta / max) * 100, v: max * 100 };
	}

	function regionHsvToHex(hue, saturation, brightness) {
		const h = ((Number(hue) % 360) + 360) % 360;
		const s = clampRegionColorValue(saturation, 0, 100) / 100;
		const v = clampRegionColorValue(brightness, 0, 100) / 100;
		const chroma = v * s;
		const x = chroma * (1 - Math.abs(((h / 60) % 2) - 1));
		const m = v - chroma;
		let red = 0;
		let green = 0;
		let blue = 0;
		if (h < 60) [red, green, blue] = [chroma, x, 0];
		else if (h < 120) [red, green, blue] = [x, chroma, 0];
		else if (h < 180) [red, green, blue] = [0, chroma, x];
		else if (h < 240) [red, green, blue] = [0, x, chroma];
		else if (h < 300) [red, green, blue] = [x, 0, chroma];
		else [red, green, blue] = [chroma, 0, x];
		return `#${[red, green, blue].map((component) => Math.round((component + m) * 255).toString(16).padStart(2, '0')).join('')}`;
	}

	function renderRegionColorControls() {
		const hue = clampRegionColorValue(state.regionHue, 0, 360);
		const saturation = clampRegionColorValue(state.regionSaturation, 0, 100);
		const brightness = clampRegionColorValue(state.regionBrightness, 0, 100);
		const surface = byId('regionColorSurface');
		const surfaceIndicator = byId('regionColorSurfaceIndicator');
		const hueControl = byId('regionColorHue');
		const hueIndicator = byId('regionColorHueIndicator');
		surface?.style.setProperty('--picker-hue', String(hue));
		if (surfaceIndicator) {
			surfaceIndicator.style.left = `${saturation}%`;
			surfaceIndicator.style.top = `${100 - brightness}%`;
			surfaceIndicator.style.setProperty('--picker-color', state.regionColor);
		}
		if (hueControl) {
			hueControl.setAttribute('aria-valuenow', String(Math.round(hue)));
			hueControl.setAttribute('aria-valuetext', `${Math.round(hue)}°`);
		}
		if (hueIndicator) hueIndicator.style.left = `${(hue / 360) * 100}%`;
	}

	function regionColorPickerIsOpen() {
		return byId('regionColorTrigger')?.getAttribute('aria-expanded') === 'true';
	}

	function openRegionColorPicker() {
		byId('regionColorPanel').hidden = false;
		byId('regionColorTrigger').setAttribute('aria-expanded', 'true');
		requestAnimationFrame(() => byId('regionColorSurface')?.focus());
	}

	function closeRegionColorPicker({ restore = false } = {}) {
		byId('regionColorPanel').hidden = true;
		byId('regionColorTrigger').setAttribute('aria-expanded', 'false');
		if (restore) byId('regionColorTrigger').focus();
	}

	function commitRegionColorInput() {
		const color = normalizeRegionColor(byId('regionColorHex').value);
		if (!color) {
			byId('regionColorHex').value = state.regionColor.toUpperCase();
			return false;
		}
		setRegionColor(color);
		return true;
	}

	function setRegionColor(color, { preserveHsv = false } = {}) {
		const normalized = normalizeRegionColor(color);
		if (!normalized) return;
		state.regionColor = normalized;
		if (!preserveHsv) {
			const hsv = regionHexToHsv(normalized);
			state.regionHue = hsv.h;
			state.regionSaturation = hsv.s;
			state.regionBrightness = hsv.v;
		}
		byId('regionColor').value = normalized;
		byId('regionColorHex').value = normalized.toUpperCase();
		byId('regionColorValue').textContent = normalized.toUpperCase();
		byId('regionColorSwatch').style.setProperty('--picker-color', normalized);
		document.querySelectorAll('[data-region-color]').forEach((button) => button.classList.toggle('active', button.dataset.regionColor === normalized));
		renderRegionColorControls();
	}

	function setRegionColorFromHsv({ h = state.regionHue, s = state.regionSaturation, v = state.regionBrightness }) {
		state.regionHue = clampRegionColorValue(h, 0, 360);
		state.regionSaturation = clampRegionColorValue(s, 0, 100);
		state.regionBrightness = clampRegionColorValue(v, 0, 100);
		setRegionColor(regionHsvToHex(state.regionHue, state.regionSaturation, state.regionBrightness), { preserveHsv: true });
	}

	function updateRegionColorSurfaceFromPointer(event) {
		const surface = byId('regionColorSurface');
		if (!surface) return;
		const bounds = surface.getBoundingClientRect();
		setRegionColorFromHsv({
			s: ((event.clientX - bounds.left) / bounds.width) * 100,
			v: 100 - ((event.clientY - bounds.top) / bounds.height) * 100
		});
	}

	function updateRegionColorHueFromPointer(event) {
		const hueControl = byId('regionColorHue');
		if (!hueControl) return;
		const bounds = hueControl.getBoundingClientRect();
		setRegionColorFromHsv({ h: ((event.clientX - bounds.left) / bounds.width) * 360 });
	}

	function renderRegionPalette() {
		const palette = byId('regionPalette');
		palette.replaceChildren();
		REGION_COLORS.forEach((color) => {
			const button = document.createElement('button');
			button.type = 'button';
			button.className = 'region-color-swatch';
			button.dataset.regionColor = color;
			button.style.setProperty('--swatch-color', color);
			button.setAttribute('aria-label', label('regionColorPreset', { color }));
			button.classList.toggle('active', state.regionColor.toLowerCase() === color);
			button.onclick = () => setRegionColor(color);
			palette.append(button);
		});
	}

	function normalizeLocale(value) {
		const locale = String(value || FALLBACK_LOCALE).trim().toLowerCase();
		return /^[a-z]{2}(?:-[a-z0-9]{2,8})*$/.test(locale) ? locale : FALLBACK_LOCALE;
	}

	async function loadDictionary(locale) {
		if (dictionaryCache.has(locale)) return dictionaryCache.get(locale);
		const request = fetch(`/editor/i18n/${encodeURIComponent(locale)}.json`, { cache: 'no-store' }).then((response) => {
			if (!response.ok) throw new Error('dictionaryUnavailable');
			return response.json();
		});
		dictionaryCache.set(locale, request);
		try {
			return await request;
		} catch (error) {
			dictionaryCache.delete(locale);
			throw error;
		}
	}

	async function applyLocale(requestedLocale) {
		const requestId = ++localeRequestId;
		let resolvedLocale = normalizeLocale(requestedLocale);
		let dictionary;
		try {
			dictionary = await loadDictionary(resolvedLocale);
		} catch {
			resolvedLocale = FALLBACK_LOCALE;
			dictionary = await loadDictionary(FALLBACK_LOCALE);
		}
		if (requestId !== localeRequestId) return;
		state.locale = resolvedLocale;
		document.documentElement.lang = resolvedLocale;
		setLabels(dictionary);
		document.documentElement.dataset.i18nReady = 'true';
	}

	function textStyleFor(type) {
		return {
			...(FALLBACK_TEXT_STYLES[type] || FALLBACK_TEXT_STYLES.Other_mountains),
			...(state.metadata?.textStyles?.[type] || {})
		};
	}

	function cssFontFamily(name) {
		const family = String(name || 'Georgia').replace(/["\\]/g, '').trim() || 'Georgia';
		return `"${family}", "Palatino Linotype", "Book Antiqua", Georgia, serif`;
	}

	function previewFontSize(type) {
		const entries = Object.keys(state.labels.textTypes || {});
		const sizes = entries.map((entry) => Number(textStyleFor(entry).size)).filter(Number.isFinite);
		const size = Number(textStyleFor(type).size);
		if (!Number.isFinite(size) || !sizes.length) return 17;
		const minimum = Math.min(...sizes);
		const maximum = Math.max(...sizes);
		if (maximum <= minimum) return 17;
		return Math.round(14 + ((size - minimum) / (maximum - minimum)) * 10);
	}

	function rgbaWithAlpha(color, alpha = 0.9) {
		const value = String(color || '').trim();
		const rgb = value.match(/^rgba?\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)/i);
		if (rgb) return `rgba(${rgb[1]}, ${rgb[2]}, ${rgb[3]}, ${alpha})`;
		const hex = value.match(/^#([0-9a-f]{6})$/i);
		if (hex) {
			const number = Number.parseInt(hex[1], 16);
			return `rgba(${(number >> 16) & 255}, ${(number >> 8) & 255}, ${number & 255}, ${alpha})`;
		}
		return `rgba(4, 1, 1, ${alpha})`;
	}

	function applyTextStylePreview(element, type) {
		if (!element) return;
		const style = textStyleFor(type);
		const outline = rgbaWithAlpha(style.boldBackgroundColor, 0.92);
		element.style.setProperty('--text-style-family', cssFontFamily(style.fontName || style.fontFamily));
		element.style.setProperty('--text-style-size', `${previewFontSize(type)}px`);
		element.style.setProperty('--text-style-weight', String(Number(style.weight) >= 600 ? 700 : 400));
		element.style.setProperty('--text-style-font-style', style.italic ? 'italic' : 'normal');
		element.style.setProperty('--text-style-color', style.color || 'var(--text)');
		element.style.setProperty('--text-style-spacing', type === 'Title' ? '0.035em' : type === 'Region' ? '0.02em' : 'normal');
		element.style.setProperty('--text-style-shadow', style.boldBackground
			? `-1px -1px 0 ${outline}, 1px -1px 0 ${outline}, -1px 1px 0 ${outline}, 1px 1px 0 ${outline}, 0 2px 5px rgba(0, 0, 0, 0.38)`
			: '0 1px 3px rgba(0, 0, 0, 0.5)');
	}

	function textStyleMeta(type) {
		const style = textStyleFor(type);
		const family = String(style.fontName || style.fontFamily || 'Serif');
		const size = Number(style.size);
		return Number.isFinite(size) ? `${family} · ${Math.round(size)}` : family;
	}

	function textTypeDropdownIsOpen() {
		return byId('textTypeDropdown')?.dataset.open === 'true';
	}

	function positionTextTypeMenu() {
		const dropdown = byId('textTypeDropdown');
		const menu = byId('textTypeMenu');
		const trigger = byId('textTypeTrigger');
		const panel = document.querySelector('.tool-panel');
		if (!dropdown || !menu || !trigger || !panel || menu.hidden) return;

		if (menu.parentElement !== panel) panel.append(menu);

		const panelBounds = panel.getBoundingClientRect();
		const triggerBounds = trigger.getBoundingClientRect();
		const edge = 12;
		const gap = 7;
		const availableBelow = Math.max(0, panelBounds.bottom - triggerBounds.bottom - edge - gap);
		const availableAbove = Math.max(0, triggerBounds.top - panelBounds.top - edge - gap);
		const desiredHeight = Math.min(430, menu.scrollHeight || 430);
		const placeTop = availableBelow < Math.min(300, desiredHeight) && availableAbove > availableBelow;
		const availableHeight = placeTop ? availableAbove : availableBelow;
		const left = Math.max(edge, triggerBounds.left - panelBounds.left);
		const width = Math.max(0, Math.min(triggerBounds.width, panelBounds.width - left - edge));

		dropdown.dataset.placement = placeTop ? 'top' : 'bottom';
		menu.style.left = `${left}px`;
		menu.style.right = 'auto';
		menu.style.width = `${width}px`;
		menu.style.maxHeight = `${Math.max(96, Math.min(desiredHeight, availableHeight))}px`;
		menu.style.top = placeTop ? 'auto' : `${triggerBounds.bottom - panelBounds.top + gap}px`;
		menu.style.bottom = placeTop ? `${panelBounds.bottom - triggerBounds.top + gap}px` : 'auto';
	}

	function closeTextTypeDropdown({ restoreFocus = false } = {}) {
		const dropdown = byId('textTypeDropdown');
		const menu = byId('textTypeMenu');
		const trigger = byId('textTypeTrigger');
		if (!dropdown || !menu || !trigger) return;
		dropdown.dataset.open = 'false';
		menu.hidden = true;
		menu.style.removeProperty('left');
		menu.style.removeProperty('right');
		menu.style.removeProperty('top');
		menu.style.removeProperty('bottom');
		menu.style.removeProperty('width');
		menu.style.removeProperty('max-height');
		trigger.setAttribute('aria-expanded', 'false');
		if (restoreFocus) trigger.focus();
	}

	function openTextTypeDropdown({ focusSelected = false } = {}) {
		const dropdown = byId('textTypeDropdown');
		const menu = byId('textTypeMenu');
		const trigger = byId('textTypeTrigger');
		const panel = document.querySelector('.tool-panel');
		if (!dropdown || !menu || !trigger || !panel || !menu.children.length) return;
		if (menu.parentElement !== panel) panel.append(menu);
		dropdown.dataset.open = 'true';
		menu.hidden = false;
		trigger.setAttribute('aria-expanded', 'true');
		positionTextTypeMenu();
		if (focusSelected) {
			(menu.querySelector('[aria-selected="true"]') || menu.querySelector('.text-style-option'))?.focus();
		}
	}

	function setTextTypeValue(value, { notify = false } = {}) {
		const select = byId('textType');
		const trigger = byId('textTypeTrigger');
		const menu = byId('textTypeMenu');
		if (!select || !trigger || !menu) return;
		const next = state.labels.textTypes?.[value] ? value : 'Other_mountains';
		select.value = next;
		const text = state.labels.textTypes?.[next] || next;
		const sample = trigger.querySelector('.text-style-sample');
		const meta = trigger.querySelector('.text-style-meta');
		if (sample) {
			sample.textContent = text;
			applyTextStylePreview(sample, next);
		}
		if (meta) meta.textContent = textStyleMeta(next);
		menu.querySelectorAll('.text-style-option').forEach((option) => {
			option.setAttribute('aria-selected', String(option.dataset.value === next));
		});
		if (notify) select.dispatchEvent(new Event('change', { bubbles: true }));
	}

	function moveTextTypeFocus(current, direction) {
		const options = [...byId('textTypeMenu').querySelectorAll('.text-style-option')];
		if (!options.length) return;
		const index = Math.max(0, options.indexOf(current));
		options[(index + direction + options.length) % options.length].focus();
	}

	function fillTextTypes() {
		const select = byId('textType');
		const menu = byId('textTypeMenu');
		const current = select?.value || 'Other_mountains';
		if (!select || !menu) return;
		select.replaceChildren();
		menu.replaceChildren();
		Object.entries(state.labels.textTypes).forEach(([value, text]) => {
			const option = document.createElement('option');
			option.value = value;
			option.textContent = text;
			select.append(option);

			const button = document.createElement('button');
			button.type = 'button';
			button.className = 'text-style-option';
			button.dataset.value = value;
			button.setAttribute('role', 'option');
			button.setAttribute('aria-selected', 'false');

			const copy = document.createElement('span');
			copy.className = 'text-style-option-copy';
			const sample = document.createElement('span');
			sample.className = 'text-style-sample';
			sample.textContent = text;
			applyTextStylePreview(sample, value);
			const meta = document.createElement('small');
			meta.className = 'text-style-meta';
			meta.textContent = textStyleMeta(value);
			copy.append(sample, meta);

			const check = document.createElement('span');
			check.className = 'text-style-option-check';
			check.setAttribute('aria-hidden', 'true');
			check.textContent = '✓';
			button.append(copy, check);
			button.onclick = () => {
				setTextTypeValue(value, { notify: true });
				closeTextTypeDropdown({ restoreFocus: true });
			};
			button.onkeydown = (event) => {
				if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
					event.preventDefault();
					moveTextTypeFocus(button, event.key === 'ArrowDown' ? 1 : -1);
				}
				if (event.key === 'Home' || event.key === 'End') {
					event.preventDefault();
					const options = menu.querySelectorAll('.text-style-option');
					options[event.key === 'Home' ? 0 : options.length - 1]?.focus();
				}
				if (event.key === 'Escape' || event.key === 'Tab') {
					if (event.key === 'Escape') event.preventDefault();
					closeTextTypeDropdown({ restoreFocus: event.key === 'Escape' });
				}
			};
			menu.append(button);
		});
		setTextTypeValue(state.labels.textTypes[current] ? current : 'Other_mountains');
	}

	function setSessionMetadata(metadata) {
		state.metadata = metadata || null;
		closeTextTypeDropdown();
		fillTextTypes();
	}

	function setStatus(text) {
		byId('statusText').textContent = text || '';
	}

	function showToast(text) {
		if (!text) return;
		const toast = byId('appToast');
		toast.querySelector('span').textContent = text;
		toast.hidden = false;
		clearTimeout(toastTimer);
		toastTimer = window.setTimeout(() => { toast.hidden = true; }, 3200);
	}

	function showLoading(title, text, progress = 0) {
		byId('loadingTitle').textContent = title;
		byId('loadingText').textContent = text || '';
		state.loadingProgress = 0;
		setLoadingProgress(progress);
		loading.hidden = false;
	}

	function hideLoading() {
		loading.hidden = true;
	}

	function setLoadingProgress(percent) {
		const value = Math.max(state.loadingProgress, Math.max(0, Math.min(100, Math.round(percent))));
		state.loadingProgress = value;
		const progress = byId('loadingProgress');
		progress.setAttribute('aria-valuenow', String(value));
		progress.querySelector('span').style.width = `${value}%`;
	}

	function formatBytes(bytes) {
		const value = Number(bytes) || 0;
		const formatter = new Intl.NumberFormat(state.locale || FALLBACK_LOCALE, { maximumFractionDigits: 2 });
		if (value >= 1024 ** 3) return `${formatter.format(value / 1024 ** 3)} ${label('gigabytes')}`;
		return `${formatter.format(value / 1024 ** 2)} ${label('megabytes')}`;
	}

	function renderHostState() {
		const host = state.host;
		byId('projectTitle').textContent = state.projectName || host.projects.find((project) => project.id === host.selectedProjectId)?.name || label('editorTitle');
		const hostError = host.errorCode ? state.labels.errors[host.errorCode] || label('genericError') : '';
		const hostStatus = host.statusCode ? state.labels.statuses[host.statusCode] || '' : '';
		if (!host.statusCode) state.lastHostStatus = '';
		else if (hostStatus && host.statusCode !== state.lastHostStatus) {
			state.lastHostStatus = host.statusCode;
			if (host.statusCode === 'saved' || host.statusCode === 'exported') showToast(hostStatus);
		}
		setStatus(hostError || hostStatus || (host.saving ? label('save') : host.exporting ? label('export') : state.dirty ? label('unsaved') : state.sessionId ? label('ready') : ''));
		byId('saveProject').disabled = !state.sessionId || host.saving;
		const exportButton = byId('exportProject');
		const exportBusy = state.exportPreparing || host.exporting;
		exportButton.disabled = !state.sessionId || exportBusy;
		exportButton.classList.toggle('loading', exportBusy);
		exportButton.setAttribute('aria-busy', String(exportBusy));
		const storage = host.storage;
		const meter = byId('storageMeter');
		meter.hidden = !storage;
		if (storage) {
			byId('storageUsed').textContent = `${formatBytes(storage.usedBytes)} / ${formatBytes(storage.limitBytes)}`;
			byId('storageAvailable').textContent = `${label('storageAvailable')}: ${formatBytes(storage.availableBytes)}`;
			const percent = storage.limitBytes > 0 ? Math.min(100, storage.usedBytes / storage.limitBytes * 100) : 0;
			const progress = meter.querySelector('.storage-progress');
			progress.setAttribute('aria-valuemin', '0');
			progress.setAttribute('aria-valuemax', String(storage.limitBytes));
			progress.setAttribute('aria-valuenow', String(Math.min(storage.usedBytes, storage.limitBytes)));
			progress.querySelector('span').style.width = `${percent}%`;
		}
		renderProjects();
		if (host.sourceCodeURL) byId('forkLink').href = host.sourceCodeURL;
		byId('backLink').href = '#';
	}

	function renderProjects() {
		const container = byId('projectList');
		container.replaceChildren();
		if (!state.host.projects.length) {
			const empty = document.createElement('p');
			empty.className = 'project-empty';
			empty.textContent = label('noProjects');
			container.append(empty);
			return;
		}
		state.host.projects.forEach((project) => {
			const card = document.createElement('article');
			card.className = `project-card${project.id === state.host.selectedProjectId ? ' active' : ''}`;
			const main = document.createElement('button');
			main.type = 'button';
			main.className = 'project-main';
			const preview = document.createElement('span');
			preview.className = 'project-preview';
			const previewSource = state.projectPreviewData[project.id] || project.previewUrl;
			if (previewSource) {
				const image = document.createElement('img');
				image.src = previewSource;
				image.alt = label('projectPreviewAlt', { name: project.name });
				image.width = 32;
				image.height = 32;
				image.loading = 'lazy';
				image.decoding = 'async';
				image.onerror = () => {
					image.remove();
					preview.classList.add('empty');
					preview.innerHTML = iconUse('folder-open');
				};
				preview.append(image);
			} else {
				preview.classList.add('empty');
				preview.innerHTML = iconUse('folder-open');
			}
			const details = document.createElement('span');
			details.className = 'project-details';
			const name = document.createElement('strong');
			name.textContent = project.name;
			const size = document.createElement('span');
			size.textContent = formatBytes(project.size);
			details.append(name, size);
			main.append(preview, details);
			main.onclick = () => postParent('nortantis:project-open', { projectId: project.id });
			const rename = document.createElement('button');
			rename.type = 'button';
			rename.className = 'ui-button icon-only project-rename';
			rename.innerHTML = iconUse('pencil');
			rename.setAttribute('aria-label', `${label('renameProject')}: ${project.name}`);
			rename.onclick = () => openRenameProjectDialog(project);
			const remove = document.createElement('button');
			remove.type = 'button';
			remove.className = 'ui-button danger icon-only project-delete';
			remove.innerHTML = iconUse('trash');
			remove.setAttribute('aria-label', `${label('deleteProject')}: ${project.name}`);
			remove.onclick = () => openDeleteProjectDialog(project);
			card.append(main, rename, remove);
			container.append(card);
		});
	}

	function openDeleteProjectDialog(project) {
		state.deleteProjectCandidate = project;
		byId('deleteProjectMessage').textContent = label('deleteProjectConfirmation', { name: project.name });
		byId('deleteProjectDialog').showModal();
	}

	function closeDeleteProjectDialog() {
		state.deleteProjectCandidate = null;
		byId('deleteProjectDialog').close();
	}

	function openRenameProjectDialog(project) {
		state.renameProjectCandidate = project;
		byId('renameProjectName').value = project.name;
		byId('renameProjectDialog').showModal();
		byId('renameProjectName').focus();
		byId('renameProjectName').select();
	}

	function closeRenameProjectDialog() {
		state.renameProjectCandidate = null;
		byId('renameProjectDialog').close();
	}

	function postParent(type, payload = {}) {
		parent.postMessage({ type, ...payload }, '*');
	}

	function operationId(prefix) {
		const unique = globalThis.crypto?.randomUUID?.() || `${Date.now()}-${Math.random().toString(36).slice(2)}`;
		return `${prefix}-${unique}`;
	}

	async function postJson(url, body) {
		const response = await fetch(url, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
		const json = await response.json().catch(() => null);
		if (!response.ok || !json?.ok) throw new Error(json?.error || `HTTP ${response.status}`);
		return json;
	}

	function fit() {
		if (!map.width || !map.height) return;
		const bounds = canvas.getBoundingClientRect();
		state.scale = Math.min(bounds.width / map.width, bounds.height / map.height) * 0.92;
		state.panX = -map.width * state.scale / 2;
		state.panY = -map.height * state.scale / 2;
		state.hasFit = true;
		applyTransform();
	}

	function applyTransform() {
		mapSurface.style.transform = `translate(${state.panX}px, ${state.panY}px) scale(${state.scale})`;
	}

	function setPreview(base64, resetView, onReady) {
		const version = ++state.previewVersion;
		previewPatchQueue = new Promise((resolve) => {
			const image = new Image();
			image.onload = () => {
				if (version !== state.previewVersion) {
					resolve();
					return;
				}
				map.width = image.naturalWidth;
				map.height = image.naturalHeight;
				const context = map.getContext('2d');
				if (context) {
					context.clearRect(0, 0, map.width, map.height);
					context.drawImage(image, 0, 0);
					mapSurface.style.width = `${map.width}px`;
					mapSurface.style.height = `${map.height}px`;
					overlay.setAttribute('width', String(map.width));
					overlay.setAttribute('height', String(map.height));
					overlay.setAttribute('viewBox', `0 0 ${map.width} ${map.height}`);
					if (resetView || !state.hasFit) fit(); else applyTransform();
					renderOverlay();
					onReady?.();
				}
				resolve();
			};
			image.onerror = () => resolve();
			image.src = `data:image/png;base64,${base64}`;
		});
	}

	function applyPreviewPatch(base64, bounds) {
		if (!base64 || !bounds || !map.width || !map.height) return;
		const version = state.previewVersion;
		previewPatchQueue = previewPatchQueue.then(() => new Promise((resolve) => {
			const image = new Image();
			image.onload = () => {
				if (version === state.previewVersion) {
					map.getContext('2d')?.drawImage(image, Number(bounds.x), Number(bounds.y));
				}
				resolve();
			};
			image.onerror = () => resolve();
			image.src = `data:image/png;base64,${base64}`;
		}));
	}

	function createProjectThumbnailBase64() {
		if (!map.width || !map.height) return '';
		const thumbnail = document.createElement('canvas');
		thumbnail.width = 32;
		thumbnail.height = 32;
		const context = thumbnail.getContext('2d');
		if (!context) return '';
		context.fillStyle = '#120706';
		context.fillRect(0, 0, 32, 32);
		context.imageSmoothingEnabled = true;
		context.imageSmoothingQuality = 'high';
		const scale = Math.min(32 / map.width, 32 / map.height);
		const width = map.width * scale;
		const height = map.height * scale;
		context.drawImage(map, (32 - width) / 2, (32 - height) / 2, width, height);
		return thumbnail.toDataURL('image/jpeg', 0.82).slice('data:image/jpeg;base64,'.length);
	}

	function publishProjectThumbnail(force = false) {
		if (!state.sessionId) return '';
		const project = state.host.projects.find((item) => item.id === state.sessionId);
		if (!force && project?.previewUrl) return '';
		const previewBase64 = createProjectThumbnailBase64();
		if (!previewBase64) return '';
		state.projectPreviewData[state.sessionId] = `data:image/jpeg;base64,${previewBase64}`;
		renderProjects();
		postParent('nortantis:project-preview', {
			operationId: operationId('preview'),
			projectId: state.sessionId,
			previewBase64
		});
		return previewBase64;
	}

	function clearWorkspace() {
		state.sessionId = null;
		state.projectName = '';
		setSessionMetadata(null);
		state.selectedText = null;
		state.selectedAsset = null;
		state.iconAssets = null;
		state.pathPreviewPoints = [];
		state.pathPreviewPolygons = [];
		state.pathPreviewEdges = [];
		window.clearTimeout(pathPreviewTimer);
		pathPreviewVersion += 1;
		queuedPathPreview = null;
		state.cursorPoint = null;
		state.brushPolygons = [];
		state.brushEdges = [];
		state.historyGroup = null;
		state.queuedHistory = null;
		state.queuedEdit = null;
		state.dirty = false;
		state.hasFit = false;
		state.previewVersion += 1;
		map.width = 0;
		map.height = 0;
		mapSurface.removeAttribute('style');
		overlay.replaceChildren();
		emptyWorkspace.hidden = false;
		renderHostState();
	}

	function mapPoint(event) {
		if (!state.metadata || !map.width) return null;
		const bounds = map.getBoundingClientRect();
		return {
			x: (event.clientX - bounds.left) / bounds.width * state.metadata.width,
			y: (event.clientY - bounds.top) / bounds.height * state.metadata.height
		};
	}

	function projectToPreview(point) {
		if (!state.metadata) return { x: 0, y: 0 };
		return { x: point.x / state.metadata.width * map.width, y: point.y / state.metadata.height * map.height };
	}

	function projectToClient(point) {
		const bounds = map.getBoundingClientRect();
		return { x: bounds.left + point.x / state.metadata.width * bounds.width, y: bounds.top + point.y / state.metadata.height * bounds.height };
	}

	function renderOverlay() {
		overlay.replaceChildren();
		if (state.pathPreviewEdges.length) {
			state.pathPreviewEdges.filter((edge) => edge.internal).forEach((edge) => appendOverlayPath(edge.points, 'path-edge-internal'));
			state.pathPreviewEdges.filter((edge) => !edge.internal).forEach((edge) => appendOverlayPath(edge.points, 'path-edge-boundary'));
		} else {
			state.pathPreviewPolygons.forEach((polygon) => appendOverlayPolygon(polygon, 'path-cell'));
		}
		if (state.brushEdges.length) {
			state.brushEdges.filter((edge) => edge.internal).forEach((edge) => appendOverlayPath(edge.points, 'brush-edge-internal'));
			state.brushEdges.filter((edge) => !edge.internal).forEach((edge) => appendOverlayPath(edge.points, 'brush-edge-boundary'));
		} else {
			state.brushPolygons.forEach((polygon) => appendOverlayPolygon(polygon, 'brush-cell'));
		}
		const brush = brushFeedback();
		if (brush && state.cursorPoint) {
			const point = projectToPreview(state.cursorPoint);
			const radius = brush.radius / state.metadata.width * map.width;
			const circle = document.createElementNS('http://www.w3.org/2000/svg', 'circle');
			circle.setAttribute('class', 'brush-radius');
			circle.setAttribute('cx', String(point.x));
			circle.setAttribute('cy', String(point.y));
			circle.setAttribute('r', String(radius));
			overlay.append(circle);
		}
		const activeStroke = state.activeTool === 'provinces' ? state.boundaryPoints : state.activeTool === 'lines' ? state.linePoints : [];
		let previewStroke = state.pathPreviewPoints.length > 1 ? state.pathPreviewPoints : activeStroke;
		if (state.activeTool === 'provinces' && state.provinceMode === 'lasso' && previewStroke.length > 2) {
			const first = previewStroke[0];
			const last = previewStroke.at(-1);
			if (Math.hypot(first.x - last.x, first.y - last.y) > 0.001) previewStroke = [...previewStroke, first];
		}
		if (previewStroke.length > 1) {
			const path = document.createElementNS('http://www.w3.org/2000/svg', 'polyline');
			path.setAttribute('class', 'stroke-preview');
			path.setAttribute('points', previewStroke.map((point) => { const p = projectToPreview(point); return `${p.x},${p.y}`; }).join(' '));
			overlay.append(path);
		}
		if (state.selectedText) {
			const point = projectToPreview(state.textDragPoint || state.selectedText);
			const rect = document.createElementNS('http://www.w3.org/2000/svg', 'rect');
			rect.setAttribute('class', 'text-selection');
			rect.setAttribute('x', String(point.x - 70));
			rect.setAttribute('y', String(point.y - 18));
			rect.setAttribute('width', '140');
			rect.setAttribute('height', '36');
			rect.setAttribute('rx', '4');
			overlay.append(rect);
		}
	}

	function appendOverlayPolygon(polygon, className) {
		if (!Array.isArray(polygon.points) || polygon.points.length < 3) return;
		const cell = document.createElementNS('http://www.w3.org/2000/svg', 'polygon');
		cell.setAttribute('class', className);
		cell.setAttribute('points', polygon.points.map(([x, y]) => { const p = projectToPreview({ x, y }); return `${p.x},${p.y}`; }).join(' '));
		overlay.append(cell);
	}

	function appendOverlayPath(points, className) {
		if (!Array.isArray(points) || points.length < 2) return;
		const path = document.createElementNS('http://www.w3.org/2000/svg', 'polyline');
		path.setAttribute('class', className);
		path.setAttribute('points', points.map(([x, y]) => { const point = projectToPreview({ x, y }); return `${point.x},${point.y}`; }).join(' '));
		overlay.append(path);
	}

	function brushFeedback() {
		if (state.activeTool === 'terrain') return { radius: state.terrainRadius / 2, exact: true };
		if (state.activeTool === 'provinces' && (state.provinceMode === 'boundaryDraw' || state.provinceMode === 'boundaryErase')) return { radius: state.boundaryRadius, exact: false };
		if (state.activeTool === 'lines') return { radius: state.lineRadius, exact: false };
		if (state.activeTool === 'icons' && (state.iconMode === 'erase' || state.selectedAsset?.type === 'trees')) return { radius: state.treeRadius / 2, exact: true };
		return null;
	}

	function updateBrushFeedback(point) {
		state.cursorPoint = point;
		const brush = brushFeedback();
		if (!brush?.exact || !state.sessionId) {
			state.brushPolygons = [];
			state.brushEdges = [];
			renderOverlay();
			return;
		}
		state.brushPolygons = [];
		state.brushEdges = [];
		renderOverlay();
		window.clearTimeout(brushSelectionTimer);
		const version = ++brushSelectionVersion;
		const type = state.activeTool === 'icons' ? 'trees.brush' : 'terrain.brush';
		brushSelectionTimer = window.setTimeout(() => requestBrushSelection({ point, radius: brush.radius, type, version }), 45);
	}

	async function requestBrushSelection(request) {
		if (brushSelectionPending) {
			queuedBrushSelection = request;
			return;
		}
		brushSelectionPending = true;
		try {
			try {
				const json = await postJson('/api/editor/session/brush-selection', {
					sessionId: state.sessionId,
					command: { type: request.type, x: request.point.x, y: request.point.y, radius: request.radius }
				});
				if (request.version !== brushSelectionVersion) return;
				state.brushPolygons = Array.isArray(json.polygons) ? json.polygons : [];
				state.brushEdges = Array.isArray(json.edges) ? json.edges : [];
				renderOverlay();
			} catch {
				if (request.version !== brushSelectionVersion) return;
				state.brushPolygons = [];
				state.brushEdges = [];
				renderOverlay();
			}
		} finally {
			brushSelectionPending = false;
			if (queuedBrushSelection) {
				const next = queuedBrushSelection;
				queuedBrushSelection = null;
				void requestBrushSelection(next);
			}
		}
	}

	function schedulePathPreview(points, type, radius) {
		state.pathPreviewPoints = [];
		state.pathPreviewPolygons = [];
		state.pathPreviewEdges = [];
		renderOverlay();
		window.clearTimeout(pathPreviewTimer);
		const version = ++pathPreviewVersion;
		if (!state.sessionId || points.length < 2) return;
		pathPreviewTimer = window.setTimeout(() => void requestPathPreview({ points, type, radius, version }), 70);
	}

	async function requestPathPreview(request) {
		if (pathPreviewPending) {
			queuedPathPreview = request;
			return;
		}
		pathPreviewPending = true;
		try {
			try {
				const json = await postJson('/api/editor/session/path-preview', { sessionId: state.sessionId, command: { type: request.type, points: request.points, radius: request.radius } });
				if (request.version !== pathPreviewVersion) return;
				state.pathPreviewPoints = Array.isArray(json.points) ? json.points.map(([x, y]) => ({ x, y })) : [];
				state.pathPreviewPolygons = Array.isArray(json.polygons) ? json.polygons : [];
				state.pathPreviewEdges = Array.isArray(json.edges) ? json.edges : [];
				renderOverlay();
			} catch {
				if (request.version !== pathPreviewVersion) return;
				state.pathPreviewPoints = [];
				state.pathPreviewPolygons = [];
				state.pathPreviewEdges = [];
				renderOverlay();
			}
		} finally {
			pathPreviewPending = false;
			if (queuedPathPreview) {
				const next = queuedPathPreview;
				queuedPathPreview = null;
				void requestPathPreview(next);
			}
		}
	}

	async function openProject(data) {
		if (!data?.projectId || !data?.projectBase64) return;
		window.clearTimeout(pathPreviewTimer);
		pathPreviewVersion += 1;
		queuedPathPreview = null;
		state.sessionId = data.projectId;
		state.projectName = data.name || label('editorTitle');
		state.hasFit = false;
		state.dirty = false;
		state.selectedText = null;
		state.iconAssets = null;
		state.selectedAsset = null;
		state.historyGroup = null;
		state.queuedHistory = null;
		state.queuedEdit = null;
		state.pathPreviewPoints = [];
		state.pathPreviewPolygons = [];
		state.pathPreviewEdges = [];
		emptyWorkspace.hidden = true;
		renderHostState();
		showLoading(label('loadingProject'), label('opening'), 8);
		try {
			const response = await fetch('/api/editor/session/open-stream', {
				method: 'POST',
				headers: { 'Content-Type': 'application/json' },
				body: JSON.stringify({ sessionId: state.sessionId, projectBase64: data.projectBase64, previewMaxDimensionPixels: data.previewMaxDimensionPixels || 1024 })
			});
			if (!response.ok || !response.body) throw new Error(`HTTP ${response.status}`);
			await readSse(response.body, (event, payload) => {
				if (event === 'phase') {
					const phase = JSON.parse(payload);
					byId('loadingText').textContent = state.labels.phases[phase.key] || label('phaseStarting');
					setLoadingProgress(PHASE_PROGRESS[phase.key] ?? phase.progress ?? 10);
				}
				if (event === 'result') {
					const result = JSON.parse(payload);
					setSessionMetadata(result.metadata);
					setPreview(result.previewBase64, true, () => publishProjectThumbnail(false));
					setStatus(label('ready'));
				}
				if (event === 'error') throw new Error('projectOpenFailed');
			});
		} catch {
			setStatus(label('genericError'));
		} finally {
			hideLoading();
		}
	}

	async function currentProject(currentOperationId) {
		if (!state.sessionId) throw new Error('Missing editor session');
		const json = await postJson('/api/editor/session/project', { sessionId: state.sessionId, operationId: currentOperationId });
		return json.projectBase64;
	}

	async function saveProject() {
		if (!state.sessionId) return;
		const currentOperationId = operationId('save');
		setStatus(label('save'));
		try {
			const projectBase64 = await currentProject(currentOperationId);
			await previewPatchQueue;
			const previewBase64 = createProjectThumbnailBase64();
			if (previewBase64) state.projectPreviewData[state.sessionId] = `data:image/jpeg;base64,${previewBase64}`;
			postParent('nortantis:save', { operationId: currentOperationId, projectId: state.sessionId, projectBase64, previewBase64 });
			state.dirty = false;
		} catch {
			setStatus(label('genericError'));
		}
	}

	async function exportProject() {
		if (!state.sessionId || state.exportPreparing || state.host.exporting) return;
		const currentOperationId = operationId('export');
		state.exportPreparing = true;
		state.exportHostStarted = false;
		renderHostState();
		setStatus(label('export'));
		try {
			const projectBase64 = await currentProject(currentOperationId);
			await previewPatchQueue;
			const previewBase64 = createProjectThumbnailBase64();
			if (previewBase64) state.projectPreviewData[state.sessionId] = `data:image/jpeg;base64,${previewBase64}`;
			postParent('nortantis:export', { operationId: currentOperationId, projectId: state.sessionId, projectBase64, previewBase64, format: 'jpg' });
		} catch {
			state.exportPreparing = false;
			state.exportHostStarted = false;
			renderHostState();
			setStatus(label('genericError'));
		}
	}

	function setTool(tool) {
		state.activeTool = tool;
		state.boundaryPoints = [];
		state.linePoints = [];
		state.pathPreviewPoints = [];
		state.pathPreviewPolygons = [];
		state.pathPreviewEdges = [];
		pathPreviewVersion += 1;
		queuedPathPreview = null;
		state.drawing = false;
		state.cursorPoint = null;
		state.brushPolygons = [];
		state.brushEdges = [];
		brushSelectionVersion += 1;
		queuedBrushSelection = null;
		document.querySelectorAll('[data-tool-button]').forEach((button) => button.classList.toggle('active', button.dataset.toolButton === tool));
		document.querySelectorAll('[data-tool-panel]').forEach((panel) => panel.classList.toggle('active', panel.dataset.toolPanel === tool));
		if (tool === 'icons') void loadIconAssets();
		renderOverlay();
	}

	async function sendEdit(command, coalesce = false) {
		if (!state.sessionId) return null;
		const nextCommand = state.historyGroup && !command.historyGroup ? { ...command, historyGroup: state.historyGroup } : command;
		if (state.editPending && coalesce) {
			state.queuedEdit = nextCommand;
			return null;
		}
		state.editPending = true;
		try {
			const json = await postJson('/api/editor/session/edit', { sessionId: state.sessionId, command: nextCommand, returnPreview: true, omitProjectBytes: true });
			if (json.metadata) setSessionMetadata(json.metadata);
			if (json.previewBase64) setPreview(json.previewBase64, false);
			else if (json.previewPatchBase64 && json.previewPatch) applyPreviewPatch(json.previewPatchBase64, json.previewPatch);
			state.dirty = true;
			setStatus(label('unsaved'));
			return json;
		} catch {
			setStatus(label('genericError'));
			return null;
		} finally {
			state.editPending = false;
			if (state.queuedEdit) {
				const next = state.queuedEdit;
				state.queuedEdit = null;
				void sendEdit(next, true);
			} else if (state.queuedHistory) {
				const action = state.queuedHistory;
				state.queuedHistory = null;
				void applyHistory(action);
			}
		}
	}

	async function applyHistory(action) {
		if (!state.sessionId) return;
		if (state.editPending || state.queuedEdit) {
			state.queuedHistory = action;
			return;
		}
		state.editPending = true;
		try {
			const json = await postJson('/api/editor/session/history', { sessionId: state.sessionId, action });
			if (json.metadata) setSessionMetadata(json.metadata);
			if (json.previewBase64) setPreview(json.previewBase64, false);
			if (json.changed) {
				state.dirty = true;
				state.selectedText = null;
				state.textDragPoint = null;
				byId('deleteText').disabled = true;
				byId('textValue').value = '';
				setStatus(label('unsaved'));
				renderOverlay();
			}
		} catch {
			setStatus(label('genericError'));
		} finally {
			state.editPending = false;
			if (state.queuedEdit) {
				const next = state.queuedEdit;
				state.queuedEdit = null;
				void sendEdit(next, true);
			} else if (state.queuedHistory) {
				const nextAction = state.queuedHistory;
				state.queuedHistory = null;
				void applyHistory(nextAction);
			}
		}
	}

	function beginHistoryGesture() {
		state.historyCounter += 1;
		state.historyGroup = `${Date.now()}-${state.historyCounter}`;
	}

	function terrainAt(point) {
		return sendEdit({ type: 'terrain.brush', mode: state.terrainMode, x: point.x, y: point.y, radius: state.terrainRadius / 2 }, true);
	}

	async function finishBoundary() {
		if (state.boundaryPoints.length < 2) { state.boundaryPoints = []; renderOverlay(); return; }
		const points = state.boundaryPoints;
		window.clearTimeout(pathPreviewTimer);
		pathPreviewVersion += 1;
		queuedPathPreview = null;
		state.boundaryPoints = [];
		state.pathPreviewPoints = [];
		state.pathPreviewPolygons = [];
		state.pathPreviewEdges = [];
		renderOverlay();
		await sendEdit({ type: state.provinceMode === 'boundaryErase' ? 'region.boundary.erase' : 'region.boundary.draw', points, radius: state.boundaryRadius, color: state.regionColor });
	}

	async function finishIslandLasso() {
		if (state.boundaryPoints.length < 3) {
			state.boundaryPoints = [];
			state.pathPreviewPoints = [];
			state.pathPreviewPolygons = [];
			state.pathPreviewEdges = [];
			renderOverlay();
			return;
		}
		const points = state.boundaryPoints;
		window.clearTimeout(pathPreviewTimer);
		pathPreviewVersion += 1;
		queuedPathPreview = null;
		state.boundaryPoints = [];
		state.pathPreviewPoints = [];
		state.pathPreviewPolygons = [];
		state.pathPreviewEdges = [];
		renderOverlay();
		await sendEdit({ type: 'region.islands.lasso', points, color: state.regionColor });
	}

	async function regionAt(point) {
		if (state.provinceMode === 'pick') {
			try {
				const json = await postJson('/api/editor/session/region-color', { sessionId: state.sessionId, command: { x: point.x, y: point.y } });
				setRegionColor(json.color);
				byId('regionStatus').textContent = label('selectedRegion', { id: json.regionId });
			} catch {
				setStatus(label('genericError'));
			}
			return;
		}
		await sendEdit({ type: 'region.paint', x: point.x, y: point.y, color: state.regionColor });
	}

	async function finishLine() {
		if (state.linePoints.length < 2) { state.linePoints = []; renderOverlay(); return; }
		const points = state.linePoints;
		window.clearTimeout(pathPreviewTimer);
		pathPreviewVersion += 1;
		queuedPathPreview = null;
		state.linePoints = [];
		state.pathPreviewPoints = [];
		state.pathPreviewPolygons = [];
		state.pathPreviewEdges = [];
		renderOverlay();
		await sendEdit({ type: `${state.lineType}.${state.lineMode}`, points, radius: state.lineRadius, width: state.lineWidth });
	}

	async function pickText(point) {
		try {
			const json = await postJson('/api/editor/session/text-pick', { sessionId: state.sessionId, command: { x: point.x, y: point.y } });
			state.selectedText = json.text;
			state.textDragPoint = null;
			if (json.text) {
				byId('textValue').value = json.text.text;
				setTextTypeValue(json.text.textType);
				byId('deleteText').disabled = false;
				renderOverlay();
				return;
			}
			byId('deleteText').disabled = true;
			beginInlineText(point);
		} catch {
			setStatus(label('genericError'));
		}
	}

	function beginInlineText(point) {
		const client = projectToClient(point);
		inlineText.hidden = false;
		inlineText.value = '';
		inlineText.dataset.x = String(point.x);
		inlineText.dataset.y = String(point.y);
		inlineText.style.left = `${Math.min(window.innerWidth - 240, Math.max(12, client.x))}px`;
		inlineText.style.top = `${Math.min(window.innerHeight - 52, Math.max(12, client.y))}px`;
		inlineText.placeholder = label('textPlaceholder');
		inlineText.focus();
	}

	async function commitInlineText() {
		if (inlineText.hidden) return;
		const value = inlineText.value.trim();
		const x = Number(inlineText.dataset.x);
		const y = Number(inlineText.dataset.y);
		inlineText.hidden = true;
		if (!value) return;
		const json = await sendEdit({ type: 'text.add', x, y, text: value, textType: byId('textType').value || 'Other_mountains' });
		if (json?.text) state.selectedText = json.text;
	}

	async function updateSelectedText(patch = {}) {
		if (!state.selectedText) return;
		const command = {
			type: 'text.update',
			index: state.selectedText.index,
			x: patch.x ?? state.selectedText.x,
			y: patch.y ?? state.selectedText.y,
			text: patch.text ?? (byId('textValue').value.trim() || state.selectedText.text),
			textType: patch.textType ?? byId('textType').value
		};
		state.selectedText = { ...state.selectedText, ...command };
		await sendEdit(command, Boolean(patch.x !== undefined));
		renderOverlay();
	}

	async function deleteSelectedText() {
		if (!state.selectedText) return;
		await sendEdit({ type: 'text.delete', index: state.selectedText.index });
		state.selectedText = null;
		state.textDragPoint = null;
		byId('deleteText').disabled = true;
		byId('textValue').value = '';
		renderOverlay();
	}

	async function loadIconAssets() {
		if (!state.sessionId || state.iconAssets || state.iconsLoading) return;
		state.iconsLoading = true;
		byId('iconLibrary').textContent = label('opening');
		try {
			state.iconAssets = await postJson('/api/editor/assets/icons', { sessionId: state.sessionId });
			renderIconLibrary();
		} catch {
			byId('iconLibrary').textContent = label('genericError');
		} finally {
			state.iconsLoading = false;
		}
	}

	function renderIconLibrary() {
		const library = byId('iconLibrary');
		library.replaceChildren();
		const allowed = ['mountains', 'sand', 'trees', 'cities'];
		allowed.forEach((type) => {
			const data = state.iconAssets?.types?.find((entry) => entry.type === type);
			if (!data) return;
			const section = document.createElement('details');
			section.className = 'icon-category';
			section.open = true;
			const heading = document.createElement('summary');
			heading.textContent = label(type);
			const grid = document.createElement('div');
			grid.className = 'icon-grid';
			const assets = type === 'trees'
				? data.groups.flatMap((group) => group.icons.slice(0, 1).map((name) => ({ type, group: group.id, name })))
				: data.groups.flatMap((group) => group.icons.map((name) => ({ type, group: group.id, name })));
			assets.forEach((asset) => {
				const button = document.createElement('button');
				button.type = 'button';
				button.className = 'icon-tile';
				button.setAttribute('aria-label', `${label(type)}: ${asset.name}`);
				const image = document.createElement('img');
				image.alt = '';
				image.loading = 'lazy';
				image.dataset.asset = JSON.stringify(asset);
				button.append(image);
				button.onclick = () => {
					state.selectedAsset = { ...asset, artPack: state.iconAssets.artPack };
					library.querySelectorAll('.icon-tile').forEach((tile) => tile.classList.toggle('active', tile === button));
					byId('treeDensityField').hidden = type !== 'trees';
					byId('treeRadiusField').hidden = state.iconMode !== 'erase' && type !== 'trees';
					if (state.cursorPoint) updateBrushFeedback(state.cursorPoint);
				};
				grid.append(button);
				void loadIconPreview(image, asset);
			});
			section.append(heading, grid);
			library.append(section);
		});
		if (!library.children.length) library.textContent = label('emptyIcons');
	}

	async function loadIconPreview(image, asset) {
		try {
			const response = await fetch('/api/editor/assets/icon-preview', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ sessionId: state.sessionId, type: asset.type, group: asset.group, name: asset.name }) });
			if (!response.ok) return;
			const url = URL.createObjectURL(await response.blob());
			image.onload = () => URL.revokeObjectURL(url);
			image.src = url;
		} catch {
			// Missing preview leaves a neutral tile; placement data remains valid.
		}
	}

	function iconAt(point) {
		if (state.iconMode === 'erase') return sendEdit({ type: 'relief.erase', x: point.x, y: point.y, radius: state.treeRadius / 2 }, true);
		if (!state.selectedAsset) return Promise.resolve();
		const asset = state.selectedAsset;
		if (asset?.type === 'trees') {
			return sendEdit({ type: state.iconMode === 'place' ? 'trees.center.set' : 'trees.center.clear', x: point.x, y: point.y, radius: state.treeRadius / 2, artPack: asset.artPack, treeType: asset.group, density: state.treeDensity / 10 }, true);
		}
		return sendEdit({ type: 'icon.center.set', x: point.x, y: point.y, iconKind: asset.type, artPack: asset.artPack, groupId: asset.group, iconName: asset.name }, true);
	}

	function openGenerationDialog(blank) {
		if (!state.sessionId) return;
		state.generationBlank = blank;
		byId('generationDialogTitle').textContent = label(blank ? 'blankTitle' : 'generateTitle');
		byId('regionCountOptions').hidden = blank;
		byId('regionCountField').hidden = blank || !byId('customRegionCount').checked;
		byId('generationDialog').showModal();
	}

	const PHASE_PROGRESS = {
		creatingMap: 3, lowMemory: 6, graph: 12, backgroundImage: 20, icons: 30, mountains: 36, dunes: 41, trees: 46, cities: 50,
		land: 56, shores: 61, rivers: 66, ocean: 70, waves: 74, roads: 78, drawIcons: 82, text: 87, border: 91, grunge: 94, grungeJob: 95, frayedEdgesJob: 96, frayedEdges: 98, done: 100
	};

	async function generateMap(size) {
		const useCustomRegionCount = !state.generationBlank && byId('customRegionCount').checked;
		const regionCountInput = byId('regionCount');
		if (useCustomRegionCount && !regionCountInput.reportValidity()) return;
		const regionCount = useCustomRegionCount ? Number(regionCountInput.value) : null;
		byId('generationDialog').close();
		showLoading(label('generating'), label('phaseStarting'), 1);
		try {
			const response = await fetch('/api/editor/session/generate-stream', {
				method: 'POST',
				headers: { 'Content-Type': 'application/json' },
				body: JSON.stringify({ sessionId: state.sessionId, name: state.projectName, size, blank: state.generationBlank, regionCount, locale: state.locale || FALLBACK_LOCALE })
			});
			if (!response.ok || !response.body) throw new Error(`HTTP ${response.status}`);
			await readSse(response.body, (event, data) => {
				if (event === 'phase') {
					const phase = JSON.parse(data);
					byId('loadingText').textContent = state.labels.phases[phase.key] || label('phaseStarting');
					setLoadingProgress(PHASE_PROGRESS[phase.key] ?? phase.progress ?? 10);
				}
				if (event === 'result') {
					const result = JSON.parse(data);
					setSessionMetadata(result.metadata);
					state.historyGroup = null;
					state.queuedHistory = null;
					state.queuedEdit = null;
					state.hasFit = false;
					setPreview(result.previewBase64, true, () => publishProjectThumbnail(true));
					state.dirty = true;
					setStatus(label('unsaved'));
				}
				if (event === 'error') throw new Error('generationFailed');
			});
		} catch {
			setStatus(label('genericError'));
		} finally {
			hideLoading();
		}
	}

	async function readSse(stream, onEvent) {
		const reader = stream.getReader();
		const decoder = new TextDecoder();
		let buffer = '';
		while (true) {
			const { done, value } = await reader.read();
			buffer += decoder.decode(value || new Uint8Array(), { stream: !done });
			let boundary;
			while ((boundary = buffer.indexOf('\n\n')) >= 0) {
				const block = buffer.slice(0, boundary);
				buffer = buffer.slice(boundary + 2);
				let event = 'message';
				const data = [];
				block.split(/\r?\n/).forEach((line) => {
					if (line.startsWith('event:')) event = line.slice(6).trim();
					if (line.startsWith('data:')) data.push(line.slice(5).trimStart());
				});
				if (data.length) onEvent(event, data.join('\n'));
			}
			if (done) break;
		}
	}

	function panBy(dx, dy) {
		if (!state.hasFit) return;
		state.panX += dx;
		state.panY += dy;
		applyTransform();
	}

	document.querySelectorAll('[data-tool-button]').forEach((button) => { button.onclick = () => setTool(button.dataset.toolButton); });
	byId('backLink').onclick = (event) => {
		event.preventDefault();
		postParent('nortantis:back');
	};
	byId('createProjectForm').onsubmit = (event) => {
		event.preventDefault();
		const name = byId('newProjectName').value.trim() || label('defaultProjectName');
		postParent('nortantis:project-create', { name });
		byId('newProjectName').value = '';
	};
	byId('confirmProjectDelete').onclick = () => {
		const project = state.deleteProjectCandidate;
		if (!project) return;
		closeDeleteProjectDialog();
		postParent('nortantis:project-delete', { projectId: project.id });
	};
	byId('deleteProjectDialog').addEventListener('close', () => {
		state.deleteProjectCandidate = null;
	});
	byId('renameProjectForm').onsubmit = (event) => {
		event.preventDefault();
		const project = state.renameProjectCandidate;
		const name = byId('renameProjectName').value.trim();
		if (!project || !name) return;
		closeRenameProjectDialog();
		postParent('nortantis:project-rename', { projectId: project.id, name });
	};
	document.querySelectorAll('[data-rename-close]').forEach((button) => {
		button.onclick = () => closeRenameProjectDialog();
	});
	byId('renameProjectDialog').addEventListener('close', () => {
		state.renameProjectCandidate = null;
	});
	byId('saveProject').onclick = () => void saveProject();
	byId('exportProject').onclick = () => void exportProject();
	byId('generateMap').onclick = () => openGenerationDialog(false);
	byId('generateBlank').onclick = () => openGenerationDialog(true);
	byId('customRegionCount').onchange = () => {
		byId('regionCountField').hidden = !byId('customRegionCount').checked;
	};
	document.querySelectorAll('[data-generation-size]').forEach((button) => { button.onclick = () => void generateMap(Number(button.dataset.generationSize)); });
	byId('landMode').onclick = () => { state.terrainMode = 'land'; byId('landMode').classList.add('active'); byId('waterMode').classList.remove('active'); };
	byId('waterMode').onclick = () => { state.terrainMode = 'water'; byId('waterMode').classList.add('active'); byId('landMode').classList.remove('active'); };
	byId('terrainRadius').oninput = (event) => { state.terrainRadius = Number(event.target.value); byId('terrainRadiusValue').value = String(state.terrainRadius); if (state.cursorPoint) updateBrushFeedback(state.cursorPoint); };
	function setProvinceMode(mode) {
		state.provinceMode = mode;
		window.clearTimeout(pathPreviewTimer);
		pathPreviewVersion += 1;
		queuedPathPreview = null;
		state.boundaryPoints = [];
		state.pathPreviewPoints = [];
		state.pathPreviewPolygons = [];
		state.pathPreviewEdges = [];
		state.drawing = false;
		['regionPaint', 'regionPick', 'islandLasso', 'boundaryDraw', 'boundaryErase'].forEach((id) => byId(id).classList.toggle('active', id === ({ paint: 'regionPaint', pick: 'regionPick', lasso: 'islandLasso', boundaryDraw: 'boundaryDraw', boundaryErase: 'boundaryErase' })[mode]));
		byId('regionStatus').textContent = label(mode === 'lasso' ? 'islandLassoHint' : 'regionHint');
		renderOverlay();
	}
	byId('boundaryDraw').onclick = () => setProvinceMode('boundaryDraw');
	byId('boundaryErase').onclick = () => setProvinceMode('boundaryErase');
	byId('boundaryRadius').oninput = (event) => { state.boundaryRadius = Number(event.target.value); byId('boundaryRadiusValue').value = String(state.boundaryRadius); if (state.cursorPoint) updateBrushFeedback(state.cursorPoint); };
	byId('regionPaint').onclick = () => setProvinceMode('paint');
	byId('regionPick').onclick = () => setProvinceMode('pick');
	byId('islandLasso').onclick = () => setProvinceMode('lasso');
	byId('regionColorTrigger').onclick = () => {
		if (regionColorPickerIsOpen()) closeRegionColorPicker();
		else openRegionColorPicker();
	};
	const regionColorSurface = byId('regionColorSurface');
	const regionColorHue = byId('regionColorHue');
	regionColorSurface.onpointerdown = (event) => {
		event.preventDefault();
		regionColorSurface.setPointerCapture(event.pointerId);
		updateRegionColorSurfaceFromPointer(event);
	};
	regionColorSurface.onpointermove = (event) => {
		if (regionColorSurface.hasPointerCapture(event.pointerId)) updateRegionColorSurfaceFromPointer(event);
	};
	regionColorSurface.onkeydown = (event) => {
		const step = event.shiftKey ? 10 : 1;
		if (event.key === 'ArrowLeft') setRegionColorFromHsv({ s: state.regionSaturation - step });
		else if (event.key === 'ArrowRight') setRegionColorFromHsv({ s: state.regionSaturation + step });
		else if (event.key === 'ArrowUp') setRegionColorFromHsv({ v: state.regionBrightness + step });
		else if (event.key === 'ArrowDown') setRegionColorFromHsv({ v: state.regionBrightness - step });
		else return;
		event.preventDefault();
	};
	regionColorHue.onpointerdown = (event) => {
		event.preventDefault();
		regionColorHue.setPointerCapture(event.pointerId);
		updateRegionColorHueFromPointer(event);
	};
	regionColorHue.onpointermove = (event) => {
		if (regionColorHue.hasPointerCapture(event.pointerId)) updateRegionColorHueFromPointer(event);
	};
	regionColorHue.onkeydown = (event) => {
		const step = event.shiftKey ? 10 : 1;
		if (event.key === 'ArrowLeft' || event.key === 'ArrowDown') setRegionColorFromHsv({ h: state.regionHue - step });
		else if (event.key === 'ArrowRight' || event.key === 'ArrowUp') setRegionColorFromHsv({ h: state.regionHue + step });
		else if (event.key === 'Home') setRegionColorFromHsv({ h: 0 });
		else if (event.key === 'End') setRegionColorFromHsv({ h: 360 });
		else return;
		event.preventDefault();
	};
	byId('regionColorHex').onblur = () => commitRegionColorInput();
	byId('regionColorHex').onkeydown = (event) => {
		if (event.key === 'Enter') {
			event.preventDefault();
			if (commitRegionColorInput()) closeRegionColorPicker({ restore: true });
		}
		if (event.key === 'Escape') {
			event.preventDefault();
			byId('regionColorHex').value = state.regionColor.toUpperCase();
			closeRegionColorPicker({ restore: true });
		}
	};
	function setLineType(type) { state.lineType = type; byId('roadType').classList.toggle('active', type === 'road'); byId('riverType').classList.toggle('active', type === 'river'); byId('riverWidthField').hidden = type !== 'river'; }
	function setLineMode(mode) { state.lineMode = mode; byId('lineDraw').classList.toggle('active', mode === 'draw'); byId('lineErase').classList.toggle('active', mode === 'erase'); }
	byId('roadType').onclick = () => setLineType('road');
	byId('riverType').onclick = () => setLineType('river');
	byId('lineDraw').onclick = () => setLineMode('draw');
	byId('lineErase').onclick = () => setLineMode('erase');
	byId('riverWidth').oninput = (event) => { state.lineWidth = Number(event.target.value); byId('riverWidthValue').value = String(state.lineWidth); };
	byId('lineRadius').oninput = (event) => { state.lineRadius = Number(event.target.value); byId('lineRadiusValue').value = String(state.lineRadius); if (state.cursorPoint) updateBrushFeedback(state.cursorPoint); };
	byId('textValue').onchange = () => void updateSelectedText({ text: byId('textValue').value.trim() });
	byId('textTypeTrigger').onclick = () => {
		const isOpen = byId('textTypeDropdown').dataset.open === 'true';
		if (isOpen) closeTextTypeDropdown();
		else openTextTypeDropdown();
	};
	byId('textTypeTrigger').onkeydown = (event) => {
		if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
			event.preventDefault();
			openTextTypeDropdown({ focusSelected: true });
		}
		if (event.key === 'Escape') closeTextTypeDropdown();
	};
	document.addEventListener('pointerdown', (event) => {
		const dropdown = byId('textTypeDropdown');
		const menu = byId('textTypeMenu');
		if (!dropdown?.contains(event.target) && !menu?.contains(event.target)) closeTextTypeDropdown();

		const colorPicker = byId('regionColorPicker');
		if (regionColorPickerIsOpen() && !colorPicker?.contains(event.target)) closeRegionColorPicker();
	});
	document.querySelector('.tool-panel-scroll')?.addEventListener('scroll', () => {
		if (textTypeDropdownIsOpen()) positionTextTypeMenu();
	}, { passive: true });
	window.addEventListener('resize', () => {
		if (textTypeDropdownIsOpen()) positionTextTypeMenu();
	});
	byId('themeToggle').onclick = toggleTheme;
	byId('textType').onchange = () => void updateSelectedText({ textType: byId('textType').value });
	byId('deleteText').onclick = () => void deleteSelectedText();
	inlineText.onkeydown = (event) => { if (event.key === 'Enter' || event.key === 'Escape') { event.preventDefault(); void commitInlineText(); } };
	inlineText.onblur = () => void commitInlineText();
	byId('iconPlace').onclick = () => { state.iconMode = 'place'; byId('iconPlace').classList.add('active'); byId('iconErase').classList.remove('active'); byId('treeRadiusField').hidden = state.selectedAsset?.type !== 'trees'; };
	byId('iconErase').onclick = () => { state.iconMode = 'erase'; byId('iconErase').classList.add('active'); byId('iconPlace').classList.remove('active'); byId('treeRadiusField').hidden = false; if (state.cursorPoint) updateBrushFeedback(state.cursorPoint); };
	byId('treeDensity').oninput = (event) => { state.treeDensity = Number(event.target.value); byId('treeDensityValue').value = String(state.treeDensity); };
	byId('treeRadius').oninput = (event) => { state.treeRadius = Number(event.target.value); byId('treeRadiusValue').value = String(state.treeRadius); if (state.cursorPoint) updateBrushFeedback(state.cursorPoint); };

	canvas.addEventListener('pointerdown', (event) => {
		if (!state.metadata || !state.sessionId) return;
		canvas.setPointerCapture(event.pointerId);
		if (event.button === 1 || state.space) {
			state.panning = true;
			state.lastX = event.clientX;
			state.lastY = event.clientY;
			canvas.classList.add('is-panning');
			return;
		}
		if (event.button !== 0) return;
		const point = mapPoint(event);
		if (!point) return;
		beginHistoryGesture();
		updateBrushFeedback(point);
		if (state.activeTool === 'terrain') { state.drawing = true; void terrainAt(point); }
		if (state.activeTool === 'provinces') {
			if (state.provinceMode === 'boundaryDraw' || state.provinceMode === 'boundaryErase' || state.provinceMode === 'lasso') {
				window.clearTimeout(pathPreviewTimer);
				pathPreviewVersion += 1;
				queuedPathPreview = null;
				state.pathPreviewPoints = [];
				state.pathPreviewPolygons = [];
				state.pathPreviewEdges = [];
				state.drawing = true;
				state.boundaryPoints = [point];
				renderOverlay();
			}
			else void regionAt(point);
		}
		if (state.activeTool === 'lines') {
			window.clearTimeout(pathPreviewTimer);
			pathPreviewVersion += 1;
			queuedPathPreview = null;
			state.pathPreviewPoints = [];
			state.pathPreviewPolygons = [];
			state.pathPreviewEdges = [];
			state.drawing = true;
			state.linePoints = [point];
			renderOverlay();
		}
		if (state.activeTool === 'text') {
			if (state.selectedText) {
				const distance = Math.hypot(point.x - state.selectedText.x, point.y - state.selectedText.y);
				if (distance < 100) { state.draggingText = true; state.textDragPoint = point; renderOverlay(); return; }
			}
			void pickText(point);
		}
		if (state.activeTool === 'icons') { state.drawing = state.iconMode === 'erase' || state.selectedAsset?.type === 'trees'; void iconAt(point); }
	});

	canvas.addEventListener('pointermove', (event) => {
		if (state.panning) {
			panBy(event.clientX - state.lastX, event.clientY - state.lastY);
			state.lastX = event.clientX;
			state.lastY = event.clientY;
			return;
		}
		const point = mapPoint(event);
		if (!point) return;
		updateBrushFeedback(point);
		if (state.activeTool === 'terrain' && state.drawing) void terrainAt(point);
		if (state.activeTool === 'provinces' && state.drawing) {
			const previous = state.boundaryPoints.at(-1);
			if (!previous || Math.hypot(point.x - previous.x, point.y - previous.y) >= 8) {
				state.boundaryPoints.push(point);
				if (state.provinceMode === 'lasso') {
					if (state.boundaryPoints.length >= 3) schedulePathPreview([...state.boundaryPoints], 'region.islands.lasso', 0);
				} else {
					schedulePathPreview([...state.boundaryPoints], `region.boundary.${state.provinceMode === 'boundaryErase' ? 'erase' : 'draw'}`, state.boundaryRadius);
				}
			}
		}
		if (state.activeTool === 'lines' && state.drawing) {
			const previous = state.linePoints.at(-1);
			if (!previous || Math.hypot(point.x - previous.x, point.y - previous.y) >= 8) { state.linePoints.push(point); schedulePathPreview([...state.linePoints], `${state.lineType}.${state.lineMode}`, state.lineRadius); }
		}
		if (state.activeTool === 'text' && state.draggingText) { state.textDragPoint = point; renderOverlay(); }
		if (state.activeTool === 'icons' && state.drawing && (state.iconMode === 'erase' || state.selectedAsset?.type === 'trees')) void iconAt(point);
	});

	async function finishPointer() {
		if (state.activeTool === 'provinces' && state.drawing) {
			if (state.provinceMode === 'lasso') await finishIslandLasso();
			else await finishBoundary();
		}
		if (state.activeTool === 'lines' && state.drawing) await finishLine();
		if (state.activeTool === 'text' && state.draggingText && state.textDragPoint) {
			const point = state.textDragPoint;
			state.selectedText = { ...state.selectedText, x: point.x, y: point.y };
			state.textDragPoint = null;
			await updateSelectedText({ x: point.x, y: point.y });
		}
		state.drawing = false;
		state.draggingText = false;
		state.panning = false;
		state.historyGroup = null;
		canvas.classList.remove('is-panning');
	}
	canvas.addEventListener('pointerup', () => void finishPointer());
	canvas.addEventListener('pointercancel', () => void finishPointer());
	canvas.addEventListener('pointerleave', () => {
		if (state.drawing || state.panning) return;
		state.cursorPoint = null;
		state.brushPolygons = [];
		state.brushEdges = [];
		state.pathPreviewPoints = [];
		state.pathPreviewPolygons = [];
		state.pathPreviewEdges = [];
		window.clearTimeout(pathPreviewTimer);
		pathPreviewVersion += 1;
		queuedPathPreview = null;
		brushSelectionVersion += 1;
		queuedBrushSelection = null;
		renderOverlay();
	});
	canvas.addEventListener('contextmenu', (event) => event.preventDefault());
	canvas.addEventListener('wheel', (event) => {
		if (!state.metadata || !map.width) return;
		event.preventDefault();
		const previousScale = state.scale;
		state.scale = Math.max(0.08, Math.min(8, state.scale * (event.deltaY < 0 ? 1.1 : 0.9)));
		const ratio = state.scale / previousScale;
		const bounds = canvas.getBoundingClientRect();
		const cursorX = event.clientX - bounds.left - bounds.width / 2;
		const cursorY = event.clientY - bounds.top - bounds.height / 2;
		state.panX = cursorX - (cursorX - state.panX) * ratio;
		state.panY = cursorY - (cursorY - state.panY) * ratio;
		applyTransform();
	}, { passive: false });

	window.addEventListener('keydown', (event) => {
		if (event.target instanceof HTMLInputElement || event.target instanceof HTMLSelectElement) return;
		if (event.code === 'Space') { state.space = true; event.preventDefault(); }
		if ((event.ctrlKey || event.metaKey) && event.code === 'KeyZ') { event.preventDefault(); void applyHistory(event.shiftKey ? 'redo' : 'undo'); return; }
		if ((event.ctrlKey || event.metaKey) && event.code === 'KeyY') { event.preventDefault(); void applyHistory('redo'); return; }
		if ((event.ctrlKey || event.metaKey) && event.code === 'KeyS') { event.preventDefault(); void saveProject(); return; }
		const step = event.shiftKey ? 72 : 32;
		if (event.code === 'ArrowUp' || event.code === 'KeyW') panBy(0, step);
		if (event.code === 'ArrowDown' || event.code === 'KeyS') panBy(0, -step);
		if (event.code === 'ArrowLeft' || event.code === 'KeyA') panBy(step, 0);
		if (event.code === 'ArrowRight' || event.code === 'KeyD') panBy(-step, 0);
	});
	window.addEventListener('keyup', (event) => { if (event.code === 'Space') state.space = false; });
	window.addEventListener('resize', () => { if (!state.hasFit) fit(); else applyTransform(); });
	window.addEventListener('storage', (event) => {
		if (event.key === THEME_STORAGE_KEY) applyTheme(event.newValue, { persist: false });
	});
	window.addEventListener('message', (event) => {
		if (event.source !== parent || !event.data || typeof event.data !== 'object') return;
		if (event.data.type === 'nortantis:host-state') {
			applyTheme(event.data.theme, { persist: false });
			state.host = { ...state.host, ...event.data.state };
			if (state.host.exporting) state.exportHostStarted = true;
			else if (state.exportHostStarted) {
				state.exportPreparing = false;
				state.exportHostStarted = false;
			}
			const activeProject = state.host.projects.find((project) => project.id === state.sessionId);
			if (activeProject) state.projectName = activeProject.name;
			renderHostState();
			void applyLocale(event.data.locale).then(() => {
				if (!state.host.projects.length && state.sessionId) clearWorkspace();
				else if (!state.host.projects.length) emptyWorkspace.hidden = false;
			});
		}
		if (event.data.type === 'nortantis:open') void openProject(event.data);
	});

	applyTheme(readStoredTheme(), { persist: false });
	void applyLocale(FALLBACK_LOCALE).finally(() => postParent('nortantis:ready'));
})();
