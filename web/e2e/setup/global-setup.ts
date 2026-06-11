import { chromium, firefox, webkit, type Browser } from '@playwright/test';
import fs from 'node:fs';
import path from 'node:path';

const stateDir = path.resolve(__dirname, '..', '.state');
fs.mkdirSync(stateDir, { recursive: true });

async function createUserState(
  browserFactory: () => Promise<Browser>,
  fileName: string,
  name: string,
  color: string
) {
  const browser = await browserFactory();
  const context = await browser.newContext();
  const page = await context.newPage();

  await context.addInitScript(
    ({ userName, userColor }) => {
      localStorage.setItem('userName', userName);
      localStorage.setItem('userColor', userColor);
    },
    { userName: name, userColor: color }
  );

  await page.goto('about:blank');
  await page.waitForTimeout(500);

  await context.storageState({ path: path.join(stateDir, fileName) });
  await context.close();
  await browser.close();
}

export default async function globalSetup() {
  await Promise.all([
    createUserState(() => chromium.launch(), 'user1.json', 'Alice', '#ff5555'),
    createUserState(() => firefox.launch(), 'user2.json', 'Bob', '#55ff55'),
    createUserState(() => webkit.launch(), 'user3.json', 'Charlie', '#5555ff'),
  ]);
}
