# Hardcoded Color Token Audit

> **Date:** 2026-08-28
> **Scope:** `PC/src/` — all `.vue` and `.css` files
> **Purpose:** Identify every hardcoded `#hex`, `rgb()`, `rgba()` value that should become a `--tu-*` CSS variable
> **Status:** Ready for T9 (tokens.css 6-theme expansion) and T12 (dark mode)

---

## Summary

| Metric | Count |
|--------|-------|
| Files with hardcoded colors | 14 |
| Total hardcoded color instances | 47 |
| Distinct new `--tu-*` variables needed | 15 |
| Naive UI theme override instances | 8 (AppLayout.vue) |

---

## Existing Variables (tokens.css)

These are already defined — **do not create duplicates**:

| Variable | Light Value | Dark Value |
|----------|-------------|------------|
| `--tu-primary` | `#2b3a67` | _(same)_ |
| `--tu-primary-hover` | `#3a4d85` | _(same)_ |
| `--tu-primary-pressed` | `#22305a` | _(same)_ |
| `--tu-accent` | `#7c3aed` | _(same)_ |
| `--tu-accent-hover` | `#8d55f0` | _(same)_ |
| `--tu-success` | `#18a058` | _(same)_ |
| `--tu-error` | `#d03050` | _(same)_ |
| `--tu-warning` | `#f0a020` | _(same)_ |
| `--tu-info` | `#2080f0` | _(same)_ |
| `--tu-bg` | `#f4f5fa` | `#16181f` |
| `--tu-surface` | `#ffffff` | `#1e212b` |
| `--tu-sidebar-bg` | `rgba(255,255,255,0.72)` | `linear-gradient(...)` |
| `--tu-border` | `rgba(43,58,103,0.14)` | `rgba(255,255,255,0.12)` |
| `--tu-text` | `#24283a` | `#e6e8f0` |
| `--tu-text-secondary` | `#5c6379` | `#a3aac0` |
| `--tu-text-disabled` | `#9aa1b5` | `#666d84` |

---

## New Variables Needed

These must be added to `tokens.css` (T9 task):

| Variable | Suggested Default (Light) | Purpose |
|----------|--------------------------|---------|
| `--tu-text-on-accent` | `#ffffff` | White text on accent/primary backgrounds |
| `--tu-accent-surface` | `rgba(124, 58, 237, 0.06)` | Very light accent background |
| `--tu-accent-overlay` | `rgba(124, 58, 237, 0.12)` | Light accent overlay (gradients, decorative) |
| `--tu-accent-hover-surface` | `rgba(124, 58, 237, 0.25)` | Hover/active accent background |
| `--tu-accent-glow` | `rgba(124, 58, 237, 0.30)` | Focus ring / selected glow |
| `--tu-accent-rail` | `rgba(124, 58, 237, 0.15)` | Progress bar rail background |
| `--tu-accent-border` | `rgba(124, 58, 237, 0.35)` | Active accent border |
| `--tu-primary-surface` | `rgba(43, 58, 103, 0.12)` | Light primary background (answered, active) |
| `--tu-primary-overlay` | `rgba(43, 58, 103, 0.35)` | Primary overlay (answered dot) |
| `--tu-success-surface` | `rgba(24, 160, 88, 0.07)` | Light success background |
| `--tu-success-border` | `rgba(24, 160, 88, 0.35)` | Success border accent |
| `--tu-error-surface` | `rgba(208, 48, 80, 0.07)` | Light error background |
| `--tu-surface-muted` | `rgba(127, 127, 140, 0.08)` | Muted neutral surface (skeleton, shortcut bar) |
| `--tu-code-bg` | `rgba(127, 127, 127, 0.10)` | Code block / pre background |
| `--tu-scrollbar-thumb` | `rgba(127, 127, 140, 0.35)` | Scrollbar thumb |
| `--tu-scrollbar-thumb-hover` | `rgba(127, 127, 140, 0.55)` | Scrollbar thumb hover |

---

## Hardcoded Color Instances

### `styles/base.css`

| Line | Current Value | Suggested Variable | Context |
|------|--------------|-------------------|---------|
| 72 | `rgba(127, 127, 127, 0.1)` | `--tu-code-bg` | `.rich-text pre` background |
| 98 | `rgba(208, 48, 80, 0.08)` | `--tu-error-surface` | `.katex-error` background |
| 118 | `rgba(124, 58, 237, 0.18)` | `--tu-accent-glow` | `.option-row:hover` box-shadow ring |
| 175 | `rgba(127, 127, 140, 0.35)` | `--tu-scrollbar-thumb` | `::-webkit-scrollbar-thumb` |
| 180 | `rgba(127, 127, 140, 0.55)` | `--tu-scrollbar-thumb-hover` | `::-webkit-scrollbar-thumb:hover` |

### `components/layout/AppLayout.vue` — Naive UI Theme Overrides

> **Note:** These are JS strings passed to `<n-config-provider :theme-overrides>`. They cannot use `var()` directly. T9 should implement a `computed` property that reads CSS custom properties via `getComputedStyle(document.documentElement)`.

| Line | Current Value | Suggested Variable | Context |
|------|--------------|-------------------|---------|
| 37 | `'#2B3A67'` | `--tu-primary` | `common.primaryColor` |
| 38 | `'#3A4D85'` | `--tu-primary-hover` | `common.primaryColorHover` |
| 39 | `'#22305A'` | `--tu-primary-pressed` | `common.primaryColorPressed` |
| 40 | `'#7C3AED'` | `--tu-accent` | `common.primaryColorSuppl` |
| 41 | `'#2080F0'` | `--tu-info` | `common.infoColor` |
| 42 | `'#18A058'` | `--tu-success` | `common.successColor` |
| 43 | `'#F0A020'` | `--tu-warning` | `common.warningColor` |
| 44 | `'#D03050'` | `--tu-error` | `common.errorColor` |

### `components/layout/QuestionPanel.vue`

| Line | Current Value | Suggested Variable | Context |
|------|--------------|-------------------|---------|
| 67 | `color="#7C3AED"` | `var(--tu-accent)` | `<n-progress>` color attribute |
| 68 | `rail-color="rgba(124,58,237,0.15)"` | `var(--tu-accent-rail)` | `<n-progress>` rail-color attribute |
| 190 | `rgba(43, 58, 103, 0.35)` | `--tu-primary-overlay` | `.dot.answered` background |
| 221 | `rgba(43, 58, 103, 0.14)` | `--tu-primary-surface` | `.cell.answered` background |
| 227 | `#fff` | `--tu-text-on-accent` | `.cell.current` color |
| 228 | `rgba(124, 58, 237, 0.3)` | `--tu-accent-glow` | `.cell.current` box-shadow |

### `components/layout/SideNav.vue`

| Line | Current Value | Suggested Variable | Context |
|------|--------------|-------------------|---------|
| 79 | `'#7c3aed'` | `var(--tu-accent)` | User avatar background (JS `:style` binding) |
| 106 | `#232736`, `#1a1d27` | `var(--tu-sidebar-bg)` | `html.dark .side-nav` background — **duplicate of tokens.css value, should use variable** |
| 157 | `rgba(43, 58, 103, 0.1)` | `--tu-primary-surface` | `.nav-item.active` background |
| 158 | `rgba(124, 58, 237, 0.35)` | `--tu-accent-border` | `.nav-item.active` border-color |

### `components/layout/AnalysisPanel.vue`

| Line | Current Value | Suggested Variable | Context |
|------|--------------|-------------------|---------|
| 327 | `rgba(127, 127, 140, 0.06)` | `--tu-surface-muted` | `.user-answer` background |
| 331 | `rgba(24, 160, 88, 0.35)` | `--tu-success-border` | `.correct-answer` border-color |
| 332 | `rgba(24, 160, 88, 0.05)` | `--tu-success-surface` | `.correct-answer` background |

### `components/question-renderers/OptionRow.vue`

| Line | Current Value | Suggested Variable | Context |
|------|--------------|-------------------|---------|
| 59 | `rgba(124, 58, 237, 0.25)` | `--tu-accent-glow` | `.opt.selected` box-shadow ring |
| 64 | `rgba(24, 160, 88, 0.07)` | `--tu-success-surface` | `.opt.correct` background |
| 69 | `rgba(208, 48, 80, 0.06)` | `--tu-error-surface` | `.opt.wrong` background |
| 85 | `#fff` | `--tu-text-on-accent` | `.badge` color |

### `components/question-renderers/JudgeRenderer.vue`

| Line | Current Value | Suggested Variable | Context |
|------|--------------|-------------------|---------|
| 81 | `rgba(124, 58, 237, 0.25)` | `--tu-accent-glow` | `.judge-btn.active` box-shadow |
| 86 | `rgba(24, 160, 88, 0.08)` | `--tu-success-surface` | `.judge-btn.good` background |
| 91 | `rgba(208, 48, 80, 0.07)` | `--tu-error-surface` | `.judge-btn.bad` background |

### `components/question-renderers/ClozeRenderer.vue`

| Line | Current Value | Suggested Variable | Context |
|------|--------------|-------------------|---------|
| 144 | `rgba(124, 58, 237, 0.06)` | `--tu-accent-surface` | `.blank-cell.active` background |
| 161 | `#fff` | `--tu-text-on-accent` | `.blank-index` color |

### `components/question-renderers/OrderingRenderer.vue`

| Line | Current Value | Suggested Variable | Context |
|------|--------------|-------------------|---------|
| 147 | `#fff` | `--tu-text-on-accent` | `.seq-badge` color |

### `components/common/LazyImage.vue`

| Line | Current Value | Suggested Variable | Context |
|------|--------------|-------------------|---------|
| 117 | `rgba(127, 127, 140, 0.08)` | `--tu-surface-muted` | Image placeholder background |

### `components/common/RichText.vue`

| Line | Current Value | Suggested Variable | Context |
|------|--------------|-------------------|---------|
| 96-97 | `#000` | _(skip)_ | CSS `mask-image` — black is a mask convention, not a visual color |

### `views/StatsView.vue`

| Line | Current Value | Suggested Variable | Context |
|------|--------------|-------------------|---------|
| 147 | `stroke="#2B3A67"` | `var(--tu-primary)` | SVG polyline — trend count line |
| 155 | `stroke="#7C3AED"` | `var(--tu-accent)` | SVG polyline — accuracy line |
| 274 | `rgba(124, 58, 237, 0.12)` | `--tu-accent-overlay` | `.rank` background |
| 319 | `#2b3a67` | `var(--tu-primary)` | `.lg.solid` legend bar |
| 323 | `#7c3aed` | `var(--tu-accent)` | `.lg.dash` gradient legend bar |

### `views/LoginView.vue`

| Line | Current Value | Suggested Variable | Context |
|------|--------------|-------------------|---------|
| 169 | `rgba(124, 58, 237, 0.12)` | `--tu-accent-overlay` | `.login-page` radial gradient |
| 170 | `rgba(43, 58, 103, 0.18)` | `--tu-primary-overlay` | `.login-page` radial gradient |

### `views/PracticeView.vue`

| Line | Current Value | Suggested Variable | Context |
|------|--------------|-------------------|---------|
| 826 | `rgba(124, 58, 237, 0.25)` | `--tu-accent-hover-surface` | `.split-divider:hover` background |
| 849 | `rgba(127, 127, 140, 0.08)` | `--tu-surface-muted` | `.shortcut-bar` background |

---

## Recurring Patterns

These hardcoded colors appear repeatedly and should be prioritized:

| Pattern | Occurrences | Suggested Variable |
|---------|-------------|-------------------|
| `rgba(124, 58, 237, 0.06–0.35)` (accent at various opacities) | 14 | `--tu-accent-*` family |
| `rgba(43, 58, 103, 0.10–0.35)` (primary at various opacities) | 5 | `--tu-primary-surface`, `--tu-primary-overlay` |
| `rgba(24, 160, 88, 0.05–0.35)` (success at various opacities) | 5 | `--tu-success-surface`, `--tu-success-border` |
| `rgba(208, 48, 80, 0.06–0.08)` (error at various opacities) | 3 | `--tu-error-surface` |
| `rgba(127, 127, 140, 0.06–0.55)` (neutral gray at various opacities) | 5 | `--tu-surface-muted`, `--tu-scrollbar-*` |
| `#fff` / `#ffffff` (white on accent backgrounds) | 4 | `--tu-text-on-accent` |
| Direct `--tu-*` value duplication in components | 2 | Should reference variable instead |

---

## Implementation Notes

### Naive UI `themeOverrides` (AppLayout.vue)

The 8 hardcoded hex values in `AppLayout.vue` lines 37-44 are passed as JavaScript strings to `<n-config-provider :theme-overrides>`. They **cannot** use `var()` syntax. T9 should:

1. Create a `computed` property that reads CSS custom properties:
   ```js
   const themeOverrides = computed(() => {
     const root = getComputedStyle(document.documentElement)
     return {
       common: {
         primaryColor: root.getPropertyValue('--tu-primary').trim(),
         primaryColorHover: root.getPropertyValue('--tu-primary-hover').trim(),
         // ... etc
       }
     }
   })
   ```
2. This ensures Naive UI stays in sync when themes switch.

### SVG Inline Attributes

SVG attributes like `stroke="#2B3A67"` (StatsView.vue) cannot use CSS variables directly in HTML attributes. Solutions:
- Use `:stroke="accentColor"` with a JS variable
- Or use `style="stroke: var(--tu-accent)"` binding

### CSS `mask-image` (RichText.vue)

The `#000` in `mask-image: linear-gradient(to bottom, #000 70%, transparent)` is **not** a visual color — it's a CSS mask convention (black = visible, transparent = hidden). This should **not** be replaced with a CSS variable.

### Dark Mode Considerations (T12)

Most `rgba()` values use the same opacity in light and dark mode. However:
- `rgba(127, 127, 140, ...)` (neutral gray) may need different base colors in dark mode
- `--tu-text-on-accent` likely stays `#fff` in both modes
- Scrollbar thumb opacity may need adjustment for dark backgrounds
- Accent glows (`rgba(124, 58, 237, 0.25)`) work in both modes but may need `!important` overrides

---

## Files Covered

| File | Hardcoded Count | Priority |
|------|----------------|----------|
| `styles/base.css` | 5 | High |
| `components/layout/AppLayout.vue` | 8 | High |
| `components/layout/QuestionPanel.vue` | 6 | High |
| `components/layout/SideNav.vue` | 4 | High |
| `components/layout/AnalysisPanel.vue` | 3 | Medium |
| `components/question-renderers/OptionRow.vue` | 4 | High |
| `components/question-renderers/JudgeRenderer.vue` | 3 | Medium |
| `components/question-renderers/ClozeRenderer.vue` | 2 | Medium |
| `components/question-renderers/OrderingRenderer.vue` | 1 | Low |
| `components/common/LazyImage.vue` | 1 | Low |
| `components/common/RichText.vue` | 0 (skip) | N/A |
| `views/StatsView.vue` | 5 | Medium |
| `views/LoginView.vue` | 2 | Low |
| `views/PracticeView.vue` | 2 | Medium |

**Total: 46 actionable instances across 13 files (1 skipped)**
