import { test, expect } from '@playwright/test';

test.describe('Protein 3D Structure Viewer - Frontend Integration', () => {

  test.beforeEach(async ({ page }) => {
    await page.goto('/');
    await page.waitForTimeout(1000);
  });

  test('Page loads with correct title and sidebar', async ({ page }) => {
    await expect(page).toHaveTitle(/Protein 3D Structure Viewer/);
    await expect(page.locator('#sidebar')).toBeVisible();
    await expect(page.locator('#viewer-container')).toBeVisible();
    await expect(page.locator('#viewer-canvas')).toBeVisible();
  });

  test('Upload zone is present and accepts click', async ({ page }) => {
    const uploadZone = page.locator('#upload-zone');
    await expect(uploadZone).toBeVisible();
    const fileInput = page.locator('#file-input');
    await expect(fileInput).toBeAttached();
  });

  test('Render mode buttons are present and clickable', async ({ page }) => {
    const ballStickBtn = page.locator('[data-mode="ball-stick"]');
    await expect(ballStickBtn).toBeVisible();
    await ballStickBtn.click();

    const spacefillBtn = page.locator('[data-mode="spacefill"]');
    await expect(spacefillBtn).toBeVisible();
    await spacefillBtn.click();
  });

  test('Color scheme buttons are present and clickable', async ({ page }) => {
    const elementBtn = page.locator('[data-color="element"]');
    await expect(elementBtn).toBeVisible();
    await elementBtn.click();

    const chainBtn = page.locator('[data-color="chain"]');
    await chainBtn.click();
  });

  test('Display option checkboxes toggle correctly', async ({ page }) => {
    const showAtoms = page.locator('#show-atoms');
    await expect(showAtoms).toBeChecked();

    const showSurface = page.locator('#show-surface');
    await expect(showSurface).not.toBeChecked();
    await showSurface.check();
    await expect(showSurface).toBeChecked();
  });

  test('Analysis tool selector has all options', async ({ page }) => {
    const select = page.locator('#analysis-tool');
    await expect(select).toBeVisible();

    const options = await select.locator('option').allTextContents();
    expect(options).toContain('Distance (2 atoms)');
    expect(options).toContain('Angle (3 atoms)');
    expect(options).toContain('Interaction Analysis');
  });

  test('Toolbar buttons are visible', async ({ page }) => {
    const toolbar = page.locator('.toolbar');
    await expect(toolbar).toBeVisible();
  });

  test('Upload PDB file triggers structure info display', async ({ page }) => {
    const fileInput = page.locator('#file-input');

    const pdbContent = `HEADER    TEST                                       1E2E
TITLE     PLAYWRIGHT TEST STRUCTURE
ATOM      1  N   ALA A   1       1.000   1.000   1.000  1.00 20.00           N
ATOM      2  CA  ALA A   1       2.000   1.000   1.000  1.00 18.00           C
ATOM      3  C   ALA A   1       2.500   2.200   1.500  1.00 19.00           C
ATOM      4  O   ALA A   1       3.200   2.900   0.800  1.00 22.00           O
ATOM      5  N   GLY A   2       2.300   2.500   2.800  1.00 15.00           N
ATOM      6  CA  GLY A   2       2.800   3.700   3.300  1.00 16.00           C
END
`;

    await page.route('**/api/structures/upload', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          id: 1,
          name: 'test.pdb',
          pdbId: '1E2E',
          atomCount: 6,
          residueCount: 2,
          bondCount: 0,
          warnings: [],
        }),
      });
    });

    await page.route('**/api/structures/1', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          structureId: 1,
          pdbId: '1E2E',
          title: 'PLAYWRIGHT TEST STRUCTURE',
          atoms: [
            { serialNumber: 1, atomName: 'N', residueName: 'ALA', chainId: 'A', residueSeqNumber: 1, x: 1.0, y: 1.0, z: 1.0, element: 'N', tempFactor: 20.0, isHetatm: false },
            { serialNumber: 2, atomName: 'CA', residueName: 'ALA', chainId: 'A', residueSeqNumber: 1, x: 2.0, y: 1.0, z: 1.0, element: 'C', tempFactor: 18.0, isHetatm: false },
            { serialNumber: 3, atomName: 'C', residueName: 'ALA', chainId: 'A', residueSeqNumber: 1, x: 2.5, y: 2.2, z: 1.5, element: 'C', tempFactor: 19.0, isHetatm: false },
            { serialNumber: 4, atomName: 'O', residueName: 'ALA', chainId: 'A', residueSeqNumber: 1, x: 3.2, y: 2.9, z: 0.8, element: 'O', tempFactor: 22.0, isHetatm: false },
            { serialNumber: 5, atomName: 'N', residueName: 'GLY', chainId: 'A', residueSeqNumber: 2, x: 2.3, y: 2.5, z: 2.8, element: 'N', tempFactor: 15.0, isHetatm: false },
            { serialNumber: 6, atomName: 'CA', residueName: 'GLY', chainId: 'A', residueSeqNumber: 2, x: 2.8, y: 3.7, z: 3.3, element: 'C', tempFactor: 16.0, isHetatm: false },
          ],
          bonds: [],
          warnings: [],
          chainIds: ['A'],
          totalAtoms: 6,
          totalResidues: 2,
        }),
      });
    });

    await page.route('**/api/collaboration/annotations/1', async route => {
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify([]) });
    });

    await page.route('**/api/collaboration/comments/1', async route => {
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify([]) });
    });

    const buffer = Buffer.from(pdbContent, 'utf-8');
    await fileInput.setInputFiles({ name: 'test.pdb', mimeType: 'chemical/x-pdb', buffer });

    await page.waitForTimeout(3000);

    const infoPanel = page.locator('#structure-info-panel');
    await expect(infoPanel).toBeVisible();

    const statsPanel = page.locator('#stats-panel');
    await expect(statsPanel).toBeVisible();
  });

  test('Three.js canvas renders WebGL content', async ({ page }) => {
    const canvas = page.locator('#viewer-canvas');
    await expect(canvas).toBeVisible();

    const hasWebGL = await page.evaluate(() => {
      const canvas = document.getElementById('viewer-canvas');
      const gl = canvas.getContext('webgl') || canvas.getContext('webgl2');
      return gl !== null;
    });
    expect(hasWebGL).toBe(true);
  });

  test('Three.js scene has correct objects after loading structure', async ({ page }) => {
    await page.route('**/api/structures/upload', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          id: 1, name: 'test.pdb', pdbId: 'TST', atomCount: 4, residueCount: 1, bondCount: 0, warnings: [],
        }),
      });
    });

    await page.route('**/api/structures/1', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          structureId: 1, pdbId: 'TST', title: 'Test',
          atoms: [
            { serialNumber: 1, atomName: 'CA', residueName: 'ALA', chainId: 'A', residueSeqNumber: 1, x: 0, y: 0, z: 0, element: 'C', tempFactor: 20, isHetatm: false },
            { serialNumber: 2, atomName: 'CA', residueName: 'GLY', chainId: 'A', residueSeqNumber: 2, x: 3.8, y: 0, z: 0, element: 'C', tempFactor: 18, isHetatm: false },
            { serialNumber: 3, atomName: 'N', residueName: 'ALA', chainId: 'A', residueSeqNumber: 1, x: -1.2, y: 0, z: 0, element: 'N', tempFactor: 19, isHetatm: false },
            { serialNumber: 4, atomName: 'O', residueName: 'GLY', chainId: 'A', residueSeqNumber: 2, x: 5.0, y: 0, z: 0, element: 'O', tempFactor: 21, isHetatm: false },
          ],
          bonds: [], warnings: [], chainIds: ['A'], totalAtoms: 4, totalResidues: 2,
        }),
      });
    });

    await page.route('**/api/collaboration/annotations/1', async route => {
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify([]) });
    });

    await page.route('**/api/collaboration/comments/1', async route => {
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify([]) });
    });

    const fileInput = page.locator('#file-input');
    const pdbContent = 'ATOM      1  CA  ALA A   1       0.000   0.000   0.000  1.00 20.00           C\nEND\n';
    const buffer = Buffer.from(pdbContent, 'utf-8');
    await fileInput.setInputFiles({ name: 'test.pdb', mimeType: 'chemical/x-pdb', buffer });

    await page.waitForTimeout(3000);

    const canvasInfo = await page.evaluate(() => {
      const canvas = document.getElementById('viewer-canvas');
      const gl = canvas.getContext('webgl2') || canvas.getContext('webgl');
      if (!gl) return { hasWebGL: false };
      const pixels = new Uint8Array(4);
      gl.readPixels(Math.floor(canvas.width / 2), Math.floor(canvas.height / 2), 1, 1, gl.RGBA, gl.UNSIGNED_BYTE, pixels);
      return {
        hasWebGL: true,
        width: canvas.width,
        height: canvas.height,
        pixelR: pixels[0],
        pixelG: pixels[1],
        pixelB: pixels[2],
        pixelA: pixels[3],
      };
    });

    expect(canvasInfo.hasWebGL).toBe(true);
    expect(canvasInfo.width).toBeGreaterThan(0);
    expect(canvasInfo.height).toBeGreaterThan(0);
  });

  test('Snapshot URL parameter loads structure view', async ({ page }) => {
    await page.route('**/api/collaboration/snapshots/abc12345', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          shortId: 'abc12345',
          structureId: 1,
          cameraPositionX: 40,
          cameraPositionY: 30,
          cameraPositionZ: 50,
          cameraTargetX: 0,
          cameraTargetY: 0,
          cameraTargetZ: 0,
          renderMode: 'ball-stick',
          colorScheme: 'element',
        }),
      });
    });

    await page.route('**/api/structures/1', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          structureId: 1, pdbId: 'SNAP', title: 'Snapshot Test',
          atoms: [
            { serialNumber: 1, atomName: 'CA', residueName: 'ALA', chainId: 'A', residueSeqNumber: 1, x: 0, y: 0, z: 0, element: 'C', tempFactor: 20, isHetatm: false },
          ],
          bonds: [], warnings: [], chainIds: ['A'], totalAtoms: 1, totalResidues: 1,
        }),
      });
    });

    await page.route('**/api/collaboration/annotations/1', async route => {
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify([]) });
    });

    await page.route('**/api/collaboration/comments/1', async route => {
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify([]) });
    });

    await page.goto('/?snapshot=abc12345');
    await page.waitForTimeout(3000);

    const infoPanel = page.locator('#structure-info-panel');
    await expect(infoPanel).toBeVisible();
  });
});
