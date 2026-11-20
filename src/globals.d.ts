// Minimum needed to typecheck without @types/node: the native module is loaded through
// require('expo-modules-core').
declare const require: (id: string) => unknown;
declare const module: { exports: unknown } | undefined;
