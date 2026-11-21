// Renders each page to HTML at build time, so the site works and reads well without JavaScript.
import {readFile, writeFile, rm} from 'node:fs/promises';
import {pathToFileURL} from 'node:url';
import {resolve} from 'node:path';

const dist = resolve('dist');
const {pages} = await import(pathToFileURL(resolve('dist-ssr/render.js')).href);
for (const [file, render] of Object.entries(pages)) {
  const path = resolve(dist, file);
  const html = await readFile(path, 'utf8');
  if (!html.includes('<!--app-->')) throw new Error(`${file}: no <!--app--> marker`);
  await writeFile(path, html.replace('<!--app-->', render()));
  console.log(`prerendered ${file}`);
}
await rm(resolve('dist-ssr'), {recursive: true, force: true});
