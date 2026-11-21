import {renderToString} from 'react-dom/server';
import {Home} from './pages/Home.jsx';
import {Privacy} from './pages/Privacy.jsx';

export const pages = {
  'index.html': () => renderToString(<Home />),
  'privacy.html': () => renderToString(<Privacy />),
};
