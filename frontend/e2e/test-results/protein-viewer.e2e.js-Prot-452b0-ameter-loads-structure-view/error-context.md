# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: protein-viewer.e2e.js >> Protein 3D Structure Viewer - Frontend Integration >> Snapshot URL parameter loads structure view
- Location: protein-viewer.e2e.js:220:3

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
  238 |     });
  239 | 
  240 |     await page.route('**/api/structures/1', async route => {
  241 |       await route.fulfill({
  242 |         status: 200,
  243 |         contentType: 'application/json',
  244 |         body: JSON.stringify({
  245 |           structureId: 1, pdbId: 'SNAP', title: 'Snapshot Test',
  246 |           atoms: [
  247 |             { serialNumber: 1, atomName: 'CA', residueName: 'ALA', chainId: 'A', residueSeqNumber: 1, x: 0, y: 0, z: 0, element: 'C', tempFactor: 20, isHetatm: false },
  248 |           ],
  249 |           bonds: [], warnings: [], chainIds: ['A'], totalAtoms: 1, totalResidues: 1,
  250 |         }),
  251 |       });
  252 |     });
  253 | 
  254 |     await page.route('**/api/collaboration/annotations/1', async route => {
  255 |       await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify([]) });
  256 |     });
  257 | 
  258 |     await page.route('**/api/collaboration/comments/1', async route => {
  259 |       await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify([]) });
  260 |     });
  261 | 
  262 |     await page.goto('/?snapshot=abc12345');
  263 |     await page.waitForTimeout(3000);
  264 | 
  265 |     const infoPanel = page.locator('#structure-info-panel');
> 266 |     await expect(infoPanel).toBeVisible();
      |                             ^ Error: expect(locator).toBeVisible() failed
  267 |   });
  268 | });
  269 | 
```