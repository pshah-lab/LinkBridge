import './styles.css';
import {hydrateRoot, createRoot} from 'react-dom/client';
import {Privacy} from './pages/Privacy.jsx';

const root = document.getElementById('root');
root.firstElementChild ? hydrateRoot(root, <Privacy />) : createRoot(root).render(<Privacy />);
