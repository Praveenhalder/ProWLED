'use strict';

var core = require('@capacitor/core');

const ZeroConf = core.registerPlugin('ZeroConf', {
    web: () => Promise.resolve().then(function () { return web; }).then((m) => new m.ZeroConfWeb()),
    electron: () => window.CapacitorCustomPlatform.plugins.ZeroConf,
});

const errorString = 'The plugin is not available on this platform';
const errorFn = Promise.reject(errorString);
class ZeroConfWeb extends core.WebPlugin {
    getHostname() {
        return errorFn;
    }
    // eslint-disable-next-line @typescript-eslint/no-unused-vars
    register(_request) {
        return errorFn;
    }
    // eslint-disable-next-line @typescript-eslint/no-unused-vars
    unregister(_request) {
        return errorFn;
    }
    stop() {
        return errorFn;
    }
    watch(
    // eslint-disable-next-line @typescript-eslint/no-unused-vars
    _request, 
    // eslint-disable-next-line @typescript-eslint/no-unused-vars
    _callback) {
        return errorFn;
    }
    // eslint-disable-next-line @typescript-eslint/no-unused-vars
    unwatch(_request) {
        return errorFn;
    }
    close() {
        return errorFn;
    }
}

var web = /*#__PURE__*/Object.freeze({
    __proto__: null,
    ZeroConfWeb: ZeroConfWeb
});

exports.ZeroConf = ZeroConf;
//# sourceMappingURL=plugin.cjs.js.map
