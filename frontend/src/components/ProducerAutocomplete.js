import React, { useState, useEffect, useLayoutEffect, useMemo, useRef, useCallback } from 'react';
import { createPortal } from 'react-dom';

const ChevronDownIcon = () => (
    <svg xmlns="http://www.w3.org/2000/svg" className="h-4 w-4 text-gray-400 flex-shrink-0" viewBox="0 0 20 20" fill="currentColor">
        <path fillRule="evenodd" d="M5.293 7.293a1 1 0 011.414 0L10 10.586l3.293-3.293a1 1 0 111.414 1.414l-4 4a1 1 0 01-1.414 0l-4-4a1 1 0 010-1.414z" clipRule="evenodd" />
    </svg>
);

const PlusIcon = () => (
    <svg xmlns="http://www.w3.org/2000/svg" className="h-4 w-4 flex-shrink-0" viewBox="0 0 20 20" fill="currentColor">
        <path fillRule="evenodd" d="M10 5a1 1 0 011 1v3h3a1 1 0 110 2h-3v3a1 1 0 11-2 0v-3H6a1 1 0 110-2h3V6a1 1 0 011-1z" clipRule="evenodd" />
    </svg>
);

const MAX_SUGGESTIONS = 8;

// Single-select typeahead for picking (or inline-creating) a producer.
// Suggestions are filtered client-side from an already-loaded producer list —
// no per-keystroke API calls. Only `onCreateNew` hits the network.
const ProducerAutocomplete = ({ value, producers, onSelectExisting, onCreateNew, disabled = false }) => {
    const [inputValue, setInputValue] = useState(value?.nome || '');
    const [isOpen, setIsOpen] = useState(false);
    const [isCreating, setIsCreating] = useState(false);
    const [createError, setCreateError] = useState(null);
    const [dropdownStyle, setDropdownStyle] = useState(null);
    const wrapperRef = useRef(null);
    const inputRef = useRef(null);
    const dropdownRef = useRef(null);

    // Stay in sync when the row's resolved producer changes from elsewhere
    // (e.g. this producer was just created/selected while correcting another row).
    useEffect(() => {
        setInputValue(value?.nome || '');
    }, [value?.id, value?.nome]);

    // The review table's horizontal-scroll wrapper (overflow-x-auto) forces
    // overflow-y to clip too (a standard CSS quirk: setting one axis to a
    // non-visible value makes the browser compute the other as 'auto' rather
    // than 'visible'), which silently cuts off an absolutely-positioned
    // dropdown nested inside it. Render the dropdown into a portal on
    // document.body instead, positioned from the input's screen coordinates,
    // so it escapes that clipping ancestor.
    const updateDropdownPosition = useCallback(() => {
        if (!inputRef.current) return;
        const rect = inputRef.current.getBoundingClientRect();
        setDropdownStyle({
            position: 'fixed',
            top: rect.bottom + 4,
            left: rect.left,
            width: Math.max(rect.width, 256),
        });
    }, []);

    useLayoutEffect(() => {
        if (!isOpen) return;
        updateDropdownPosition();
        window.addEventListener('scroll', updateDropdownPosition, true);
        window.addEventListener('resize', updateDropdownPosition);
        return () => {
            window.removeEventListener('scroll', updateDropdownPosition, true);
            window.removeEventListener('resize', updateDropdownPosition);
        };
    }, [isOpen, updateDropdownPosition]);

    useEffect(() => {
        function handleClickOutside(event) {
            const insideInput = wrapperRef.current && wrapperRef.current.contains(event.target);
            const insideDropdown = dropdownRef.current && dropdownRef.current.contains(event.target);
            if (!insideInput && !insideDropdown) {
                setIsOpen(false);
            }
        }
        document.addEventListener('mousedown', handleClickOutside);
        return () => document.removeEventListener('mousedown', handleClickOutside);
    }, []);

    const normalizedInput = inputValue.trim().toLowerCase();

    const filteredOptions = useMemo(() => {
        if (!normalizedInput) return producers.slice(0, MAX_SUGGESTIONS);
        return producers
            .filter(p => p.nome.toLowerCase().includes(normalizedInput))
            .slice(0, MAX_SUGGESTIONS);
    }, [producers, normalizedInput]);

    const hasExactMatch = producers.some(p => p.nome.trim().toLowerCase() === normalizedInput);
    const showCreateOption = normalizedInput.length > 0 && !hasExactMatch && !isCreating;

    const handleSelect = (producer) => {
        setInputValue(producer.nome);
        setCreateError(null);
        setIsOpen(false);
        onSelectExisting(producer);
    };

    const handleCreate = async () => {
        const typedName = inputValue.trim();
        if (!typedName || isCreating) return;
        setIsCreating(true);
        setCreateError(null);
        try {
            const created = await onCreateNew(typedName);
            setInputValue(created?.nome || typedName);
            setIsOpen(false);
        } catch (error) {
            setCreateError(error.message || 'Não foi possível criar o produtor.');
        } finally {
            setIsCreating(false);
        }
    };

    const handleKeyDown = (e) => {
        if (e.key === 'Escape') {
            setIsOpen(false);
        } else if (e.key === 'Enter') {
            e.preventDefault();
            if (filteredOptions.length === 1 && !showCreateOption) {
                handleSelect(filteredOptions[0]);
            } else if (filteredOptions.length === 0 && showCreateOption) {
                handleCreate();
            }
        }
    };

    const isUnresolved = !value?.id;

    return (
        <div className="relative" ref={wrapperRef}>
            <div className="flex items-center gap-2">
                <input
                    ref={inputRef}
                    type="text"
                    aria-label="Nome do produtor"
                    value={inputValue}
                    disabled={disabled || isCreating}
                    onChange={(e) => {
                        setInputValue(e.target.value);
                        setCreateError(null);
                        setIsOpen(true);
                    }}
                    onFocus={() => setIsOpen(true)}
                    onKeyDown={handleKeyDown}
                    className="w-full text-sm p-1.5 rounded border border-gray-300 focus:ring-1 focus:ring-blue-500 focus:outline-none disabled:bg-gray-100 disabled:text-gray-400"
                    placeholder="Nome do produtor"
                />
                <button
                    type="button"
                    aria-label="Mostrar sugestões"
                    disabled={disabled}
                    onMouseDown={(e) => e.preventDefault()}
                    onClick={() => setIsOpen(prev => !prev)}
                    className="disabled:opacity-30"
                >
                    <ChevronDownIcon />
                </button>
                {isUnresolved && (
                    <span className="whitespace-nowrap px-2 py-0.5 rounded text-xs bg-amber-100 text-amber-800">
                        Pendente
                    </span>
                )}
            </div>

            {isOpen && !disabled && dropdownStyle && createPortal(
                <div
                    ref={dropdownRef}
                    style={dropdownStyle}
                    className="z-50 bg-white shadow-lg rounded-lg border border-gray-200 py-1 max-h-64 overflow-y-auto"
                >
                    {filteredOptions.length === 0 && !showCreateOption && (
                        <p className="px-3 py-2 text-sm text-gray-500 text-center">Nenhum produtor encontrado</p>
                    )}
                    <ul>
                        {filteredOptions.map((producer) => (
                            <li
                                key={producer.id}
                                className="cursor-pointer select-none px-3 py-2 text-sm text-gray-900 hover:bg-blue-50"
                                onMouseDown={(e) => e.preventDefault()}
                                onClick={() => handleSelect(producer)}
                            >
                                {producer.nome}
                            </li>
                        ))}
                    </ul>

                    {showCreateOption && (
                        <button
                            type="button"
                            disabled={isCreating}
                            onMouseDown={(e) => e.preventDefault()}
                            onClick={handleCreate}
                            className="w-full flex items-center gap-2 text-left px-3 py-2 text-sm text-blue-700 hover:bg-blue-50 border-t border-gray-100 disabled:opacity-50"
                        >
                            <PlusIcon />
                            {isCreating ? 'Criando...' : `Criar novo produtor: "${inputValue.trim()}"`}
                        </button>
                    )}
                </div>,
                document.body
            )}

            {createError && (
                <p className="mt-1 text-xs text-red-600">{createError}</p>
            )}
        </div>
    );
};

export default ProducerAutocomplete;
