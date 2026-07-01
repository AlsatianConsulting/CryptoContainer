package dev.alsatianconsulting.cryptocontainer.ui.theme

import androidx.compose.ui.graphics.Color

// ============================================================
// CryptoContainer color tokens
//
// Anchored on the Alsatian Consulting brand: warm-dark surfaces,
// ember-orange accent (#E87722), no second accent hue. Values mirror
// the canonical token set in the design system (colors_and_type.css).
// Dark is the default surface; light mode is warm "paper" — never
// pure-white sterile. Semantic colors are reserved for state, not
// branding, and always pair with an icon in the UI.
// ============================================================

// ---------- Brand orange scale ----------
val BrandOrange = Color(0xFFE87722)        // 500 — primary accent, CTAs, active state
val BrandOrangeDeep = Color(0xFFB85510)    // 600 — pressed / deep container
val BrandOrangeHover = Color(0xFFF1914B)   // 400 — hover / secondary accent
val BrandOrangeAccentSoft = Color(0xFFFFB16B) // 300 — soft accent, gradient stop
val BrandOrangeSoft = Color(0xFFFFB784)    // 200 — outline on dark, soft container
val BrandOrangePale = Color(0xFFFFE1CC)    // 100 — pale tint, light-mode container
val BrandOrangeWash = Color(0xFFFCE7D3)    // 050 — wash, hover bg on light

// ---------- Warm dark neutrals ----------
// Not pure greys — a faint brown undertone ties the UI to the
// German-shepherd fur palette the brand is named for.
val WarmInk = Color(0xFF24170D)            // ink for text-on-accent / on light
val WarmInkSoft = Color(0xFF4C3524)        // light-mode muted text
val WarmSurfaceDark = Color(0xFF1A120C)    // app background
val WarmSurfaceDarkAlt = Color(0xFF23170F) // alt surface
val WarmSurfaceVariantDark = Color(0xFF3A2A1E)
val WarmOnDark = Color(0xFFF6EDE5)         // warm cream foreground
val WarmOnDarkMuted = Color(0xFFBBAEA4)    // muted cream — secondary copy only

// ---------- Warm paper (light mode) ----------
// Light mode is parchment, not sterile white. Surfaces carry a faint
// cream undertone that ties back to the warm-dark family.
val PaperSurface = Color(0xFFFFFCF9)       // highest surface — cards, sheets
val PaperBackground = Color(0xFFFBF4EC)    // app background — warm paper
val PaperSunken = Color(0xFFF4E9DD)        // sunken / striped rows
val PaperVariant = Color(0xFFECDCCB)       // raised-variant / hover
val PaperLine = Color(0xFFE2CDB8)          // hairline border
val PaperInk = Color(0xFF2A1B10)           // warm near-black ink
val PaperInkMuted = Color(0xFF6E5743)      // warm brown muted
val BrandOrangeOnPaper = Color(0xFF94440C) // accent pressed on paper

// ---------- Semantic state ----------
// Reserved for state, never branding. Always paired with an icon.
val StateSuccess = Color(0xFF22C55E)
val StateWarning = Color(0xFFF59E0B)
val StateError = Color(0xFFDC2626)
