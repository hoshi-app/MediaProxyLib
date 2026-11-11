// Минимум для typecheck без @types/node: натив ищем через require('expo-modules-core').
declare const require: (id: string) => unknown;
declare const module: { exports: unknown } | undefined;
