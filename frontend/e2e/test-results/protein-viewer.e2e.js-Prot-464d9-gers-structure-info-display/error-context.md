# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: protein-viewer.e2e.js >> Protein 3D Structure Viewer - Frontend Integration >> Upload PDB file triggers structure info display
- Location: protein-viewer.e2e.js:68:3

# Error details

```
Error: expect(locator).toBeVisible() failed

Locator:  locator('#structure-info-panel')
Expected: visible
Received: hidden
Timeout:  5000ms

Call log:
  - Expect "toBeVisible" with timeout 5000ms
  - waiting for locator('#structure-info-panel')
    14 × locator resolved to <div class="panel" id="structure-info-panel">…</div>
       - unexpected value "hidden"

```

```yaml
- text: Protein Structure Viewer 🧬 Drop PDB file here or click to browse Render Mode
- button "Ball & Stick"
- button "Spacefill"
- button "Wireframe"
- button "Cartoon"
- text: Color Scheme
- button "By Element"
- button "By Chain"
- button "By Residue"
- button "B-Factor"
- button "Hydrophobicity"
- text: Display Options
- checkbox "Atoms" [checked]
- text: Atoms
- checkbox "Bonds" [checked]
- text: Bonds
- checkbox "Molecular Surface"
- text: Molecular Surface
- checkbox "Electrostatic Potential"
- text: Electrostatic Potential
- checkbox "Hydrogen Bonds"
- text: Hydrogen Bonds
- checkbox "Residue Labels" [checked]
- text: Residue Labels
- checkbox "Annotations" [checked]
- text: Annotations Analysis Tools Tool
- combobox:
  - option "Distance (2 atoms)" [selected]
  - option "Angle (3 atoms)"
  - option "Interaction Analysis"
- text: Atom 1 Serial
- spinbutton
- text: Atom 2 Serial
- spinbutton
- button "Run Analysis"
- text: Structure Alignment Reference Structure ID
- spinbutton
- text: Mobile Structure ID
- spinbutton
- button "Align Structures"
- text: Annotations Type
- combobox:
  - option "Functional Domain" [selected]
  - option "Mutation Site"
  - option "Binding Pocket"
  - option "Active Site"
- text: Label
- textbox "Annotation label"
- text: Description
- textbox "Description"
- button "Add Annotation"
- text: Share & Collaborate
- button "📷 Share Snapshot"
- text: Batch Analysis Structure IDs (comma separated)
- textbox "1,2,3"
- button "Run Batch Analysis"
- button "⟲ Reset"
- button "⊕ Center"
- button "↻ Rotate"
- button "📷"
- button "💬 Comment"
```

# Test source

```ts
  37  |     await elementBtn.click();
  38  | 
  39  |     const chainBtn = page.locator('[data-color="chain"]');
  40  |     await chainBtn.click();
  41  |   });
  42  | 
  43  |   test('Display option checkboxes toggle correctly', async ({ page }) => {
  44  |     const showAtoms = page.locator('#show-atoms');
  45  |     await expect(showAtoms).toBeChecked();
  46  | 
  47  |     const showSurface = page.locator('#show-surface');
  48  |     await expect(showSurface).not.toBeChecked();
  49  |     await showSurface.check();
  50  |     await expect(showSurface).toBeChecked();
  51  |   });
  52  | 
  53  |   test('Analysis tool selector has all options', async ({ page }) => {
  54  |     const select = page.locator('#analysis-tool');
  55  |     await expect(select).toBeVisible();
  56  | 
  57  |     const options = await select.locator('option').allTextContents();
  58  |     expect(options).toContain('Distance (2 atoms)');
  59  |     expect(options).toContain('Angle (3 atoms)');
  60  |     expect(options).toContain('Interaction Analysis');
  61  |   });
  62  | 
  63  |   test('Toolbar buttons are visible', async ({ page }) => {
  64  |     const toolbar = page.locator('.toolbar');
  65  |     await expect(toolbar).toBeVisible();
  66  |   });
  67  | 
  68  |   test('Upload PDB file triggers structure info display', async ({ page }) => {
  69  |     const fileInput = page.locator('#file-input');
  70  | 
  71  |     const pdbContent = `HEADER    TEST                                       1E2E
  72  | TITLE     PLAYWRIGHT TEST STRUCTURE
  73  | ATOM      1  N   ALA A   1       1.000   1.000   1.000  1.00 20.00           N
  74  | ATOM      2  CA  ALA A   1       2.000   1.000   1.000  1.00 18.00           C
  75  | ATOM      3  C   ALA A   1       2.500   2.200   1.500  1.00 19.00           C
  76  | ATOM      4  O   ALA A   1       3.200   2.900   0.800  1.00 22.00           O
  77  | ATOM      5  N   GLY A   2       2.300   2.500   2.800  1.00 15.00           N
  78  | ATOM      6  CA  GLY A   2       2.800   3.700   3.300  1.00 16.00           C
  79  | END
  80  | `;
  81  | 
  82  |     await page.route('**/api/structures/upload', async route => {
  83  |       await route.fulfill({
  84  |         status: 200,
  85  |         contentType: 'application/json',
  86  |         body: JSON.stringify({
  87  |           id: 1,
  88  |           name: 'test.pdb',
  89  |           pdbId: '1E2E',
  90  |           atomCount: 6,
  91  |           residueCount: 2,
  92  |           bondCount: 0,
  93  |           warnings: [],
  94  |         }),
  95  |       });
  96  |     });
  97  | 
  98  |     await page.route('**/api/structures/1', async route => {
  99  |       await route.fulfill({
  100 |         status: 200,
  101 |         contentType: 'application/json',
  102 |         body: JSON.stringify({
  103 |           structureId: 1,
  104 |           pdbId: '1E2E',
  105 |           title: 'PLAYWRIGHT TEST STRUCTURE',
  106 |           atoms: [
  107 |             { serialNumber: 1, atomName: 'N', residueName: 'ALA', chainId: 'A', residueSeqNumber: 1, x: 1.0, y: 1.0, z: 1.0, element: 'N', tempFactor: 20.0, isHetatm: false },
  108 |             { serialNumber: 2, atomName: 'CA', residueName: 'ALA', chainId: 'A', residueSeqNumber: 1, x: 2.0, y: 1.0, z: 1.0, element: 'C', tempFactor: 18.0, isHetatm: false },
  109 |             { serialNumber: 3, atomName: 'C', residueName: 'ALA', chainId: 'A', residueSeqNumber: 1, x: 2.5, y: 2.2, z: 1.5, element: 'C', tempFactor: 19.0, isHetatm: false },
  110 |             { serialNumber: 4, atomName: 'O', residueName: 'ALA', chainId: 'A', residueSeqNumber: 1, x: 3.2, y: 2.9, z: 0.8, element: 'O', tempFactor: 22.0, isHetatm: false },
  111 |             { serialNumber: 5, atomName: 'N', residueName: 'GLY', chainId: 'A', residueSeqNumber: 2, x: 2.3, y: 2.5, z: 2.8, element: 'N', tempFactor: 15.0, isHetatm: false },
  112 |             { serialNumber: 6, atomName: 'CA', residueName: 'GLY', chainId: 'A', residueSeqNumber: 2, x: 2.8, y: 3.7, z: 3.3, element: 'C', tempFactor: 16.0, isHetatm: false },
  113 |           ],
  114 |           bonds: [],
  115 |           warnings: [],
  116 |           chainIds: ['A'],
  117 |           totalAtoms: 6,
  118 |           totalResidues: 2,
  119 |         }),
  120 |       });
  121 |     });
  122 | 
  123 |     await page.route('**/api/collaboration/annotations/1', async route => {
  124 |       await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify([]) });
  125 |     });
  126 | 
  127 |     await page.route('**/api/collaboration/comments/1', async route => {
  128 |       await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify([]) });
  129 |     });
  130 | 
  131 |     const buffer = Buffer.from(pdbContent, 'utf-8');
  132 |     await fileInput.setInputFiles({ name: 'test.pdb', mimeType: 'chemical/x-pdb', buffer });
  133 | 
  134 |     await page.waitForTimeout(3000);
  135 | 
  136 |     const infoPanel = page.locator('#structure-info-panel');
> 137 |     await expect(infoPanel).toBeVisible();
      |                             ^ Error: expect(locator).toBeVisible() failed
  138 | 
  139 |     const statsPanel = page.locator('#stats-panel');
  140 |     await expect(statsPanel).toBeVisible();
  141 |   });
  142 | 
  143 |   test('Three.js canvas renders WebGL content', async ({ page }) => {
  144 |     const canvas = page.locator('#viewer-canvas');
  145 |     await expect(canvas).toBeVisible();
  146 | 
  147 |     const hasWebGL = await page.evaluate(() => {
  148 |       const canvas = document.getElementById('viewer-canvas');
  149 |       const gl = canvas.getContext('webgl') || canvas.getContext('webgl2');
  150 |       return gl !== null;
  151 |     });
  152 |     expect(hasWebGL).toBe(true);
  153 |   });
  154 | 
  155 |   test('Three.js scene has correct objects after loading structure', async ({ page }) => {
  156 |     await page.route('**/api/structures/upload', async route => {
  157 |       await route.fulfill({
  158 |         status: 200,
  159 |         contentType: 'application/json',
  160 |         body: JSON.stringify({
  161 |           id: 1, name: 'test.pdb', pdbId: 'TST', atomCount: 4, residueCount: 1, bondCount: 0, warnings: [],
  162 |         }),
  163 |       });
  164 |     });
  165 | 
  166 |     await page.route('**/api/structures/1', async route => {
  167 |       await route.fulfill({
  168 |         status: 200,
  169 |         contentType: 'application/json',
  170 |         body: JSON.stringify({
  171 |           structureId: 1, pdbId: 'TST', title: 'Test',
  172 |           atoms: [
  173 |             { serialNumber: 1, atomName: 'CA', residueName: 'ALA', chainId: 'A', residueSeqNumber: 1, x: 0, y: 0, z: 0, element: 'C', tempFactor: 20, isHetatm: false },
  174 |             { serialNumber: 2, atomName: 'CA', residueName: 'GLY', chainId: 'A', residueSeqNumber: 2, x: 3.8, y: 0, z: 0, element: 'C', tempFactor: 18, isHetatm: false },
  175 |             { serialNumber: 3, atomName: 'N', residueName: 'ALA', chainId: 'A', residueSeqNumber: 1, x: -1.2, y: 0, z: 0, element: 'N', tempFactor: 19, isHetatm: false },
  176 |             { serialNumber: 4, atomName: 'O', residueName: 'GLY', chainId: 'A', residueSeqNumber: 2, x: 5.0, y: 0, z: 0, element: 'O', tempFactor: 21, isHetatm: false },
  177 |           ],
  178 |           bonds: [], warnings: [], chainIds: ['A'], totalAtoms: 4, totalResidues: 2,
  179 |         }),
  180 |       });
  181 |     });
  182 | 
  183 |     await page.route('**/api/collaboration/annotations/1', async route => {
  184 |       await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify([]) });
  185 |     });
  186 | 
  187 |     await page.route('**/api/collaboration/comments/1', async route => {
  188 |       await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify([]) });
  189 |     });
  190 | 
  191 |     const fileInput = page.locator('#file-input');
  192 |     const pdbContent = 'ATOM      1  CA  ALA A   1       0.000   0.000   0.000  1.00 20.00           C\nEND\n';
  193 |     const buffer = Buffer.from(pdbContent, 'utf-8');
  194 |     await fileInput.setInputFiles({ name: 'test.pdb', mimeType: 'chemical/x-pdb', buffer });
  195 | 
  196 |     await page.waitForTimeout(3000);
  197 | 
  198 |     const canvasInfo = await page.evaluate(() => {
  199 |       const canvas = document.getElementById('viewer-canvas');
  200 |       const gl = canvas.getContext('webgl2') || canvas.getContext('webgl');
  201 |       if (!gl) return { hasWebGL: false };
  202 |       const pixels = new Uint8Array(4);
  203 |       gl.readPixels(Math.floor(canvas.width / 2), Math.floor(canvas.height / 2), 1, 1, gl.RGBA, gl.UNSIGNED_BYTE, pixels);
  204 |       return {
  205 |         hasWebGL: true,
  206 |         width: canvas.width,
  207 |         height: canvas.height,
  208 |         pixelR: pixels[0],
  209 |         pixelG: pixels[1],
  210 |         pixelB: pixels[2],
  211 |         pixelA: pixels[3],
  212 |       };
  213 |     });
  214 | 
  215 |     expect(canvasInfo.hasWebGL).toBe(true);
  216 |     expect(canvasInfo.width).toBeGreaterThan(0);
  217 |     expect(canvasInfo.height).toBeGreaterThan(0);
  218 |   });
  219 | 
  220 |   test('Snapshot URL parameter loads structure view', async ({ page }) => {
  221 |     await page.route('**/api/collaboration/snapshots/abc12345', async route => {
  222 |       await route.fulfill({
  223 |         status: 200,
  224 |         contentType: 'application/json',
  225 |         body: JSON.stringify({
  226 |           shortId: 'abc12345',
  227 |           structureId: 1,
  228 |           cameraPositionX: 40,
  229 |           cameraPositionY: 30,
  230 |           cameraPositionZ: 50,
  231 |           cameraTargetX: 0,
  232 |           cameraTargetY: 0,
  233 |           cameraTargetZ: 0,
  234 |           renderMode: 'ball-stick',
  235 |           colorScheme: 'element',
  236 |         }),
  237 |       });
```