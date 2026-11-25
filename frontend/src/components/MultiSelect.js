import React, { useState, useEffect, useRef } from 'react';

// Icons
const ChevronDownIcon = () => (
    <svg xmlns="http://www.w3.org/2000/svg" className="h-5 w-5 text-gray-400" viewBox="0 0 20 20" fill="currentColor">
        <path fillRule="evenodd" d="M5.293 7.293a1 1 0 011.414 0L10 10.586l3.293-3.293a1 1 0 111.414 1.414l-4 4a1 1 0 01-1.414 0l-4-4a1 1 0 010-1.414z" clipRule="evenodd" />
    </svg>
);

const SearchIcon = () => (
    <svg xmlns="http://www.w3.org/2000/svg" className="h-4 w-4 text-gray-400" fill="none" viewBox="0 0 24 24" stroke="currentColor">
        <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z" />
    </svg>
);


const MultiSelect = ({ options, value, onChange, placeholder = "Selecione...", className = "" }) => {
    const [isOpen, setIsOpen] = useState(false);
    const [searchTerm, setSearchTerm] = useState('');
    const wrapperRef = useRef(null);

    // Close on click outside
    useEffect(() => {
        function handleClickOutside(event) {
            if (wrapperRef.current && !wrapperRef.current.contains(event.target)) {
                setIsOpen(false);
            }
        }
        document.addEventListener("mousedown", handleClickOutside);
        return () => document.removeEventListener("mousedown", handleClickOutside);
    }, [wrapperRef]);

    const filteredOptions = options.filter(option =>
        option.label.toLowerCase().includes(searchTerm.toLowerCase())
    );

    const handleSelect = (optionValue) => {
        const newValue = value.includes(optionValue)
            ? value.filter(v => v !== optionValue)
            : [...value, optionValue];
        onChange(newValue);
    };

    const handleSelectAll = () => {
        if (value.length === filteredOptions.length && filteredOptions.length > 0) {
            // Deselect visible
            const visibleValues = filteredOptions.map(o => o.value);
            onChange(value.filter(v => !visibleValues.includes(v)));
        } else {
            // Select visible
            const visibleValues = filteredOptions.map(o => o.value);
            const newValue = [...new Set([...value, ...visibleValues])];
            onChange(newValue);
        }
    };

    const isAllVisibleSelected = filteredOptions.length > 0 && filteredOptions.every(o => value.includes(o.value));

    // Display text
    let displayText = placeholder;
    if (value.length > 0) {
        if (value.length === options.length) displayText = "Todos selecionados";
        else if (value.length === 1) {
            const selectedOption = options.find(o => o.value === value[0]);
            displayText = selectedOption ? selectedOption.label : value[0];
        }
        else displayText = `${value.length} selecionados`;
    }

    return (
        <div className={`relative ${className}`} ref={wrapperRef}>
            <button
                type="button"
                onClick={() => setIsOpen(!isOpen)}
                className="w-full bg-white border border-gray-300 rounded-lg py-2 px-3 flex items-center justify-between text-left focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-blue-500"
            >
                <span className="block truncate text-sm text-gray-700">{displayText}</span>
                <ChevronDownIcon />
            </button>

            {isOpen && (
                <div className="absolute z-50 mt-1 w-full bg-white shadow-lg rounded-lg border border-gray-200 py-2 max-h-80 flex flex-col">
                    {/* Search */}
                    <div className="px-3 pb-2 border-b border-gray-100">
                        <div className="relative">
                            <div className="absolute inset-y-0 left-0 pl-3 flex items-center pointer-events-none">
                                <SearchIcon />
                            </div>
                            <input
                                type="text"
                                className="block w-full pl-9 pr-3 py-1.5 border border-gray-300 rounded-md leading-5 bg-gray-50 placeholder-gray-400 focus:outline-none focus:ring-1 focus:ring-blue-500 focus:border-blue-500 sm:text-sm"
                                placeholder="Buscar..."
                                value={searchTerm}
                                onChange={(e) => setSearchTerm(e.target.value)}
                                autoFocus
                            />
                        </div>
                    </div>

                    {/* Actions */}
                    <div className="px-3 py-2 flex justify-between text-xs text-blue-600 font-medium">
                        <button type="button" onClick={handleSelectAll} className="hover:text-blue-800">
                            {isAllVisibleSelected ? "Desmarcar visíveis" : "Marcar visíveis"}
                        </button>
                        <button type="button" onClick={() => onChange([])} className="hover:text-blue-800">
                            Limpar
                        </button>
                    </div>

                    {/* Options */}
                    <ul className="flex-1 overflow-y-auto px-1">
                        {filteredOptions.length === 0 ? (
                            <li className="px-4 py-2 text-sm text-gray-500 text-center">Nenhum resultado</li>
                        ) : (
                            filteredOptions.map((option) => (
                                <li
                                    key={option.value}
                                    className={`cursor-pointer select-none relative py-2 pl-3 pr-9 rounded-md hover:bg-blue-50 ${value.includes(option.value) ? 'bg-blue-50' : ''}`}
                                    onClick={() => handleSelect(option.value)}
                                >
                                    <div className="flex items-center">
                                        <input
                                            type="checkbox"
                                            checked={value.includes(option.value)}
                                            readOnly
                                            className="h-4 w-4 text-blue-600 focus:ring-blue-500 border-gray-300 rounded cursor-pointer"
                                        />
                                        <span className={`ml-3 block truncate text-sm ${value.includes(option.value) ? 'font-medium text-blue-900' : 'text-gray-900'}`}>
                                            {option.label}
                                        </span>
                                    </div>
                                </li>
                            ))
                        )}
                    </ul>
                </div>
            )}
        </div>
    );
};

export default MultiSelect;
