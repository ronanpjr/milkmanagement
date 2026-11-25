import React from 'react';

class ErrorBoundary extends React.Component {
    constructor(props) {
        super(props);
        this.state = { hasError: false, error: null, errorInfo: null };
    }

    static getDerivedStateFromError(error) {
        // Update state so the next render will show the fallback UI.
        return { hasError: true };
    }

    componentDidCatch(error, errorInfo) {
        // You can also log the error to an error reporting service
        this.setState({ error, errorInfo });
        console.error("ErrorBoundary caught an error", error, errorInfo);
    }

    render() {
        if (this.state.hasError) {
            // You can render any custom fallback UI
            return (
                <div className="p-8 text-center bg-red-50 border border-red-200 rounded-lg m-4">
                    <h2 className="text-2xl font-bold text-red-700 mb-4">Algo deu errado.</h2>
                    <p className="text-red-600 mb-4">Ocorreu um erro inesperado na aplicação.</p>
                    <details className="whitespace-pre-wrap text-left bg-white p-4 rounded border border-red-100 text-red-500 text-xs overflow-auto max-h-64">
                        {this.state.error && this.state.error.toString()}
                        <br />
                        {this.state.errorInfo && this.state.errorInfo.componentStack}
                    </details>
                    <button
                        onClick={() => window.location.reload()}
                        className="mt-6 px-4 py-2 bg-red-600 text-white rounded hover:bg-red-700 transition"
                    >
                        Recarregar Aplicação
                    </button>
                </div>
            );
        }

        return this.props.children;
    }
}

export default ErrorBoundary;
