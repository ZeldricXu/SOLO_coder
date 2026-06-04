const esbuild = require('esbuild');
const path = require('path');

const STATIC_DIR = path.resolve(__dirname, '..', 'src', 'main', 'resources', 'static');
const NODE_MODULES = path.resolve(__dirname, 'node_modules');

async function buildVendor() {
  await esbuild.build({
    entryPoints: [
      path.join(NODE_MODULES, 'alpinejs', 'dist', 'cdn.min.js'),
    ],
    bundle: false,
    minify: true,
    outfile: path.join(STATIC_DIR, 'js', 'vendor', 'alpine.min.js'),
    format: 'iife',
  });

  await esbuild.build({
    entryPoints: [
      path.join(NODE_MODULES, 'htmx.org', 'dist', 'htmx.min.js'),
    ],
    bundle: false,
    minify: true,
    outfile: path.join(STATIC_DIR, 'js', 'vendor', 'htmx.min.js'),
    format: 'iife',
  });

  await esbuild.build({
    entryPoints: [
      path.join(NODE_MODULES, 'sortablejs', 'Sortable.min.js'),
    ],
    bundle: false,
    minify: true,
    outfile: path.join(STATIC_DIR, 'js', 'vendor', 'sortable.min.js'),
    format: 'iife',
  });

  await esbuild.build({
    entryPoints: [
      path.join(NODE_MODULES, 'echarts', 'dist', 'echarts.min.js'),
    ],
    bundle: false,
    minify: true,
    outfile: path.join(STATIC_DIR, 'js', 'vendor', 'echarts.min.js'),
    format: 'iife',
  });

  console.log('Vendor bundles built successfully.');
}

buildVendor().catch((err) => {
  console.error(err);
  process.exit(1);
});
