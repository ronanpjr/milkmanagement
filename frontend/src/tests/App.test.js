import { render, screen } from '@testing-library/react';
import App from '../App';

test('renders login screen', () => {
  render(<App />);
  const linkElement = screen.getByText(/Entrar/i);
  expect(linkElement).toBeInTheDocument();
});
