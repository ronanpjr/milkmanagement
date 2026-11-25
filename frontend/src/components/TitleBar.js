import React from 'react';
import logo from '../logo.svg';

// Icons
const MinimizeIcon = () => (
    <svg width="12" height="12" viewBox="0 0 12 12"><rect y="5" width="12" height="2" fill="currentColor" /></svg>
);
const MaximizeIcon = () => (
    <svg width="12" height="12" viewBox="0 0 12 12"><rect width="12" height="12" stroke="currentColor" strokeWidth="2" fill="none" /></svg>
);
const CloseIcon = () => (
    <svg width="12" height="12" viewBox="0 0 12 12"><path d="M1 1l10 10M11 1L1 11" stroke="currentColor" strokeWidth="2" /></svg>
);

const TitleBar = () => {
    const electronAPI = window.electronAPI;

    const handleMinimize = () => electronAPI?.minimize();
    const handleMaximize = () => electronAPI?.maximize();
    const handleClose = () => electronAPI?.close();

    if (!electronAPI) return null; // Don't render in browser mode if not electron

    return (
        <div className="h-8 bg-gray-900 flex justify-between items-center text-white select-none app-drag z-50 fixed top-0 left-0 right-0 shadow-md">
            <div className="flex items-center px-3 gap-2 app-no-drag">
                <img src={logo} alt="Logo" className="h-5 w-auto" />
                <span className="text-xs font-semibold tracking-wide text-gray-300">ERP AI OCR</span>
            </div>
            <div className="flex app-no-drag">
                <button onClick={handleMinimize} className="h-8 w-10 flex items-center justify-center hover:bg-gray-700 text-gray-400 hover:text-white transition-colors">
                    <MinimizeIcon />
                </button>
                <button onClick={handleMaximize} className="h-8 w-10 flex items-center justify-center hover:bg-gray-700 text-gray-400 hover:text-white transition-colors">
                    <MaximizeIcon />
                </button>
                <button onClick={handleClose} className="h-8 w-10 flex items-center justify-center hover:bg-red-600 text-gray-400 hover:text-white transition-colors">
                    <CloseIcon />
                </button>
            </div>
        </div>
    );
};

export default TitleBar;
