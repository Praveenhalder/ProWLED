import { registerPlugin } from '@capacitor/core';
const ZeroConf = registerPlugin('ZeroConf', {
    web: () => import('./web').then((m) => new m.ZeroConfWeb()),
    electron: () => window.CapacitorCustomPlatform.plugins.ZeroConf,
});
export * from './definitions';
export { ZeroConf };
//# sourceMappingURL=index.js.map