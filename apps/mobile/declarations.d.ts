/// <reference types="expo/types" />

// expo-env.d.ts is generated (and gitignored by Expo's default template), so a fresh
// checkout — CI, a new clone — has no reference to expo/types and `import '@/global.css'`
// fails TS2882 on the first `tsc --noEmit`. This tracked twin carries the same reference;
// the two are idempotent together.
