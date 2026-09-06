---
name: LabelGuard AI
colors:
  surface: '#f8f9ff'
  surface-dim: '#cbdbf5'
  surface-bright: '#f8f9ff'
  surface-container-lowest: '#ffffff'
  surface-container-low: '#eff4ff'
  surface-container: '#e5eeff'
  surface-container-high: '#dce9ff'
  surface-container-highest: '#d3e4fe'
  on-surface: '#0b1c30'
  on-surface-variant: '#44474e'
  inverse-surface: '#213145'
  inverse-on-surface: '#eaf1ff'
  outline: '#74777f'
  outline-variant: '#c4c6cf'
  surface-tint: '#485f83'
  primary: '#00142f'
  on-primary: '#ffffff'
  primary-container: '#0f294a'
  on-primary-container: '#7a91b7'
  inverse-primary: '#b0c8f1'
  secondary: '#006a61'
  on-secondary: '#ffffff'
  secondary-container: '#86f2e4'
  on-secondary-container: '#006f66'
  tertiary: '#00113b'
  on-tertiary: '#ffffff'
  tertiary-container: '#002367'
  on-tertiary-container: '#5f8aff'
  error: '#ba1a1a'
  on-error: '#ffffff'
  error-container: '#ffdad6'
  on-error-container: '#93000a'
  primary-fixed: '#d5e3ff'
  primary-fixed-dim: '#b0c8f1'
  on-primary-fixed: '#001b3b'
  on-primary-fixed-variant: '#30476a'
  secondary-fixed: '#89f5e7'
  secondary-fixed-dim: '#6bd8cb'
  on-secondary-fixed: '#00201d'
  on-secondary-fixed-variant: '#005049'
  tertiary-fixed: '#dbe1ff'
  tertiary-fixed-dim: '#b4c5ff'
  on-tertiary-fixed: '#00174b'
  on-tertiary-fixed-variant: '#003ea8'
  background: '#f8f9ff'
  on-background: '#0b1c30'
  surface-variant: '#d3e4fe'
typography:
  headline-xl:
    fontFamily: Plus Jakarta Sans
    fontSize: 32px
    fontWeight: '700'
    lineHeight: 40px
    letterSpacing: -0.02em
  headline-lg:
    fontFamily: Plus Jakarta Sans
    fontSize: 24px
    fontWeight: '700'
    lineHeight: 32px
    letterSpacing: -0.015em
  headline-md:
    fontFamily: Plus Jakarta Sans
    fontSize: 20px
    fontWeight: '600'
    lineHeight: 28px
    letterSpacing: -0.01em
  headline-sm:
    fontFamily: Plus Jakarta Sans
    fontSize: 18px
    fontWeight: '600'
    lineHeight: 24px
    letterSpacing: -0.005em
  body-lg:
    fontFamily: Inter
    fontSize: 16px
    fontWeight: '400'
    lineHeight: 24px
    letterSpacing: -0.005em
  body-md:
    fontFamily: Inter
    fontSize: 14px
    fontWeight: '400'
    lineHeight: 20px
    letterSpacing: 0em
  body-sm:
    fontFamily: Inter
    fontSize: 12px
    fontWeight: '400'
    lineHeight: 16px
    letterSpacing: 0.01em
  label-lg:
    fontFamily: Inter
    fontSize: 14px
    fontWeight: '600'
    lineHeight: 20px
    letterSpacing: 0.01em
  label-md:
    fontFamily: Inter
    fontSize: 12px
    fontWeight: '600'
    lineHeight: 16px
    letterSpacing: 0.02em
  label-sm:
    fontFamily: Inter
    fontSize: 11px
    fontWeight: '600'
    lineHeight: 14px
    letterSpacing: 0.03em
  code-metric:
    fontFamily: Inter
    fontSize: 13px
    fontWeight: '500'
    lineHeight: 18px
    letterSpacing: -0.02em
rounded:
  sm: 0.25rem
  DEFAULT: 0.5rem
  md: 0.75rem
  lg: 1rem
  xl: 1.5rem
  full: 9999px
spacing:
  space-2xs: 0.125rem
  space-xs: 0.25rem
  space-sm: 0.5rem
  space-md: 0.75rem
  space-base: 1rem
  space-lg: 1.25rem
  space-xl: 1.5rem
  space-2xl: 2rem
  space-3xl: 2.5rem
  gutter-mobile: 1rem
  margin-mobile: 1rem
  card-padding: 1.25rem
  touch-target-min: 2.75rem
---

## Brand & Style
The design system establishes an authoritative, mission-critical regulatory interface tailored for Legal Metrology officers, standards inspectors, and enterprise compliance auditors operating in high-pressure field environments. The visual language bridges institutional authority with precision computer vision technology, delivering instantaneous clarity, high outdoor legibility under harsh sunlight, and zero-tolerance data accuracy.

Drawing from modern precision-engineered enterprise and institutional design frameworks, the aesthetic avoids playful or superficial consumer trends. Instead, it employs deliberate structural density, crisp structural division, high-contrast typography, and uncompromising informational hierarchy. The emotional baseline is rigorous, unyielding, and legally unimpeachable: every scan, dimension extraction, and violation report must feel evidentiary and definitive.

## Colors
The palette is engineered for rapid field assessment, instant statutory verification, and high outdoor legibility. 

- **Primary (`#0F294A` - Deep Slate Navy):** Anchors the institutional authority of the platform. Used for primary toolbars, active navigation states, primary buttons, high-level structural framing, and conclusive inspection headers.
- **Secondary (`#0D9488` - Precision Cyan/Teal):** Serves as the technical computer-vision accent. Utilized for live camera reticles, optical measurement vectors, automated bounding box highlights, and optical character verification tracking.
- **Tertiary (`#2563EB` - Verification Indigo):** Reserved for secondary audit actions, reference documentation links, statutory rule lookups, and neutral telemetry indicators.
- **Neutral (`#64748B` - Slate Neutral):** Provides precise tonal modulation across backgrounds (`#F8FAFC`), structural surfaces (`#FFFFFF`), subtle dividers (`#E2E8F0`), and secondary informational metadata (`#475569`).

### Semantic Status Colors
- **Compliant Emerald (`#059669` / `#10B981`):** Signals full statutory adherence, approved unit sizing, verified MRP declarations, and validated packer credentials.
- **Caution Amber (`#D97706` / `#F59E0B`):** Denotes marginal compliance discrepancies, illegible font ratios, warning-level declarations, or pending manual verifications.
- **Critical Violation Red (`#DC2626` / `#EF4444`):** Indicates immediate Legal Metrology statutory non-compliance, missing declarations, tampered pricing, or uncertified weights.

## Typography
The typographic system pairs Plus Jakarta Sans for structural headings with Inter for dense tabular, statutory, and optical reading data.

- **Plus Jakarta Sans (Headings):** Selected for its crisp geometric proportions and confident baseline, conveying statutory authority without feeling antiquated or bureaucratic.
- **Inter (Body, Labels, Metrics):** Chosen for its tall x-height, clear optical distinctions between zero and capital 'O', and superior contrast handling on anti-glare mobile screens during outdoor field inspections.
- **Data Densities & Metrics:** All numeric values (weights, measures, font-height millimeter measurements, dates of packaging) use tabular figures (`font-variant-numeric: tabular-nums`) to guarantee instantaneous scanning down column matrices.

## Layout & Spacing
The layout implements an 8-point base grid model optimized for one-handed mobile field utility and high-density evidence capture.

- **Screen Framing:** Standard mobile views leverage a 16px (`1rem`) lateral screen margin, maintaining optimal thumb-reach zones in portrait orientation. Critical actions (shutter trigger, flag violation, issue notice) are pinned to fixed bottom sheets or action strips.
- **Touch Targets:** Any interactive target—such as camera point anchors, accordion toggles, or status pills—adheres strictly to a minimum touch bounding box of 44x44px (`touch-target-min`).
- **Responsive Adaptability:** On mobile devices, views stay strictly single-column for fast vertical swiping through violation items. On tablets or foldables deployed in mobile inspection vehicles, layouts expand into a split-pane master-detail arrangement: live bounding-box feed on the left, rule violation breakdown on the right.

## Elevation & Depth
To preserve crisp distinction in direct outdoor glare, this design system avoids diffuse, low-contrast drop shadows. Depth and hierarchy are achieved through a calibrated combination of layered tonal surfaces and crisp 1px structural strokes.

- **Base Layer (Surface 0):** Neutral backdrop (`#F8FAFC`) providing neutral separation behind operational cards.
- **Card Layer (Surface 1):** Solid white (`#FFFFFF`) bound with an exact 1px solid stroke (`#E2E8F0`). Under extreme sunlight, this high-contrast separation guarantees that cards remain readable without requiring elevation blurring.
- **Interactive & Floated Layers (Surface 2):** Inspection floating toolbars, camera HUD reticles, and contextual bottom sheets feature a 1px border (`#CBD5E1`) paired with an intentional, low-spread ambient shadow: `0 4px 12px -2px rgba(15, 41, 74, 0.08), 0 2px 4px -1px rgba(15, 41, 74, 0.04)`.
- **Evidence Overlays:** Computer vision highlights on live camera feeds use semi-opaque fill tints (12% opacity) bound with crisp 2px solid vector borders in corresponding semantic colors (Emerald for detected compliant zones, Red for non-compliant declarations).

## Shapes
The shape language implements a modern `rounded-2xl` framework (`1rem` / `16px`) for cards and primary modal surfaces, balanced against fully rounded pill geometries (`9999px`) for metadata tokens, status tags, and inspection badges.

- **Inspection Cards & Containers:** Radii stay consistent at `16px` (`rounded-2xl`), offering an approachable, polished surface while maintaining clean interior alignment for tabular metric checklists.
- **Pills & Status Indicators:** Status badges, compliance chips, and statutory rule codes use full capsule curvature (`rounded-full`), preventing visual competition with rectangular input cards and data grids.
- **Field Inputs & Interactive Rows:** Buttons, dropdown selectors, and evidence upload panels use an intermediate `8px` (`rounded-lg`) radius for compact, structural efficiency.

## Components

### Buttons
- **Primary Action (Enforce / Finalize Inspection):** Solid Deep Slate Navy background (`#0F294A`), pure white text, 8px border radius, 48px height for thumb tap accuracy. Focus state displays a 2px offset ring in Cyan (`#0D9488`).
- **Secondary Action (Manual Override / Log Memo):** Surface white with a 1px solid `#CBD5E1` border, text in `#0F294A`, providing equal visual weight without conflicting with primary submissions.
- **Destructive Action (Flag Immediate Non-Compliance):** Solid Red (`#DC2626`) or high-contrast red-tinted surface (`#FEF2F2`) with red border (`#F87171`) and bold red label.

### Status Badges & Pills
- Built as capsule shapes (`rounded-full`) with a minimum height of 24px, containing a mandatory 14px semantic icon alongside high-contrast bold text.
- **Compliant:** Surface `#ECFDF5`, text `#065F46`, stroke `#A7F3D0`, leading icon checkmark.
- **Cautionary / Under Threshold:** Surface `#FFFBEB`, text `#92400E`, stroke `#FDE68A`, leading icon alert triangle.
- **Violation:** Surface `#FEF2F2`, text `#991B1B`, stroke `#FECACA`, leading icon warning octagon.

### Inspection Cards
- White background (`#FFFFFF`), 16px corner radius, 1px perimeter stroke (`#E2E8F0`). 
- Internal layout includes a dedicated header bar displaying the statutory rule clause (e.g., *Rule 6(1)(a) - Net Quantity Statement*), followed by OCR confidence scores, verified actual measurements versus minimum required font-height thresholds, and visual evidence thumbnails with inspection bounding-boxes.

### Verification Input Fields
- Structured for outdoor physical verification: 48px standard touch height, 1px border (`#CBD5E1`), background `#FFFFFF`. Active state commands an immediate 2px stroke in `#0F294A`. Number inputs include built-in unit locks (e.g., `g`, `ml`, `mm`, `₹`) rendered in medium slate text (`#64748B`) to eliminate measurement ambiguity.

### Step Indicator & Audit Progress
- Horizontal segment tracker pinned below the global header, dividing the audit lifecycle (*1. Scan Packaging*, *2. Metric Detection*, *3. Rule Verification*, *4. Notice Issuance*). Completed steps transition to solid Emerald (`#059669`), current step in Deep Slate (`#0F294A`) with an active indicator dot, and pending steps in muted stroke (`#E2E8F0`).

### Evidence Inspection Overlay (CV HUD)
- Semi-transparent camera HUD with precision corner tick marks. Detected label areas (e.g., MRP, Best Before, Manufacturer Address, Net Quantity) are enclosed by high-contrast geometric bounding boxes:
  - 2px solid cyan border for active character scanning.
  - 2px solid green border for verified legal threshold declarations.
  - 2px solid red dashed border for missing mandatory declarations or undersized typefaces, paired with an anchor-pinned status pill displaying the statutory shortfall.