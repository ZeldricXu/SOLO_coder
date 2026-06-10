import * as fs from 'fs'
import * as path from 'path'
import * as os from 'os'

export function createTempDir(prefix = 'config-flow-test-'): string {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), prefix))
  return dir
}

export function removeTempDir(dir: string): void {
  if (fs.existsSync(dir)) {
    fs.rmSync(dir, { recursive: true, force: true })
  }
}

export function writeEnvFile(dir: string, filename: string, content: string): string {
  const filePath = path.join(dir, filename)
  fs.writeFileSync(filePath, content)
  return filePath
}

export function writeTemplate(dir: string, filename: string, content: string): string {
  const filePath = path.join(dir, filename)
  fs.writeFileSync(filePath, content)
  return filePath
}

export function readOutputFile(filePath: string): string {
  return fs.readFileSync(filePath, 'utf-8')
}

export function createGitRepo(dir: string): void {
  const { execSync } = require('child_process')
  execSync('git init', { cwd: dir })
  execSync('git config user.email "test@config-flow.local"', { cwd: dir })
  execSync('git config user.name "Test User"', { cwd: dir })
}
