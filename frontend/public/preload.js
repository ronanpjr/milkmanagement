const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('electronAPI', {
    minimize: () => ipcRenderer.send('window-minimize'),
    maximize: () => ipcRenderer.send('window-maximize'),
    close: () => ipcRenderer.send('window-close'),
    saveToPdf: (filename) => ipcRenderer.invoke('save-to-pdf', filename),
});

const apiBaseUrlArg = process.argv.find((arg) => arg.startsWith('--erp-api-base-url='));
if (apiBaseUrlArg) {
    const apiBaseUrl = apiBaseUrlArg.slice('--erp-api-base-url='.length);
    contextBridge.exposeInMainWorld('__ERP_API_BASE_URL__', apiBaseUrl);
}
