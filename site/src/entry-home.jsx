import './styles.css';
import {hydrateRoot, createRoot} from 'react-dom/client';
import {Home} from './pages/Home.jsx';

const root = document.getElementById('root');
root.firstElementChild ? hydrateRoot(root, <Home />) : createRoot(root).render(<Home />);
