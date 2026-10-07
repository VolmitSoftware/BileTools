import { copyFile, mkdir, readFile, rename, rm, writeFile } from 'node:fs/promises'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const artifacts = fileURLToPath(new URL('../../../build/runtime-fixtures/', import.meta.url))

export default {
  name: 'bile-reload-recovery',
  description: 'Verify connected-player commands, dependency recovery, cooperative phases, and retired classloaders on repeated reloads.',
  async run(context) {
    const plugins = path.join(context.server.directory, 'plugins')
    const log = path.join(context.server.directory, 'logs', 'latest.log')
    const data = path.join(plugins, 'ReloadFixture')
    await mkdir(data, { recursive: true })
    const state = async (name, version, extra = '') => {
      const response = await context.command(`/${name.toLowerCase()}:fixturestate`,
        new RegExp(`FIXTURE name=${name} version=${version} load=1 enable=1.*${extra}`), 10000)
      context.expect(response !== undefined, `${name} must be enabled exactly once at version ${version}`)
      return String(response)
    }
    const replace = async (artifact) => {
      const temporary = path.join(plugins, 'ReloadFixture.jar.part')
      await copyFile(path.join(artifacts, artifact), temporary)
      await rename(temporary, path.join(plugins, 'ReloadFixture.jar'))
    }
    const reload = async (expected) => {
      const offset = (await readFile(log, 'utf8')).length
      context.bot.chat('/bile reload ReloadFixture')
      return context.waitUntil(async () => {
        const appended = (await readFile(log, 'utf8')).slice(offset)
        return expected.test(appended) ? appended : false
      }, { timeoutMs: 45000, intervalMs: 100, label: `reload outcome ${expected}` })
    }
    await context.step('verify startup dependency group and command collision owner', async () => {
      await state('ReloadFixture', '1')
      await state('DependentFixture', '1')
      await context.command('/sharedprobe', /FIXTURE name=CollisionOwner version=1/, 10000)
      await context.command('/bile inspect ReloadFixture', /recovery=true.*cooperative=true.*DependentFixture/, 10000)
    })
    await context.step('repeat three cooperative reloads without disconnecting the player', async () => {
      for (let cycle = 0; cycle < 3; cycle++) {
        await reload(/Timing reload ReloadFixture:.*health=ok/)
        await state('ReloadFixture', '1', 'draining=false')
        await state('DependentFixture', '1', 'draining=false')
        await context.command('/sharedprobe', /FIXTURE name=CollisionOwner version=1/, 10000)
      }
      context.report.reloadCycles = 3
    })
    await context.step('veto keeps the group active and cancels preparation', async () => {
      await writeFile(path.join(data, 'veto'), '')
      try {
        await reload(/refused reload: fixture veto/)
        await state('ReloadFixture', '1', 'cancel=1 draining=false')
        await state('DependentFixture', '1', 'cancel=1 draining=false')
      } finally {
        await rm(path.join(data, 'veto'), { force: true })
      }
    })
    await context.step('replace the running version while preserving dependencies', async () => {
      await replace('ReloadFixture-2.jar')
      await reload(/Timing reload ReloadFixture:.*health=ok/)
      await state('ReloadFixture', '2')
      await state('DependentFixture', '1')
    })
    await context.step('failed replacement restores the previous running group', async () => {
      await replace('ReloadFixture-broken.jar')
      const output = await reload(/Restored running version of DependentFixture/)
      context.expect(output.includes('Restored running version of ReloadFixture'), 'Recovery must restore the root plugin')
      context.expect(!output.includes('Timing reload ReloadFixture:'), 'Failed replacement must not report success')
      await state('ReloadFixture', '2')
      await state('DependentFixture', '1')
    })
    await context.step('missing replacement dependency refuses before draining', async () => {
      await replace('ReloadFixture-missing.jar')
      await reload(/Missing dependency MissingFixture/)
      await state('ReloadFixture', '2', 'prepare=0 commit=0')
      await state('DependentFixture', '1', 'prepare=0 commit=0')
    })
    await context.step('failed load restores the running group before enabling the replacement', async () => {
      await replace('ReloadFixture-load-failure.jar')
      await reload(/Restored running version of DependentFixture/)
      await state('ReloadFixture', '2')
      await state('DependentFixture', '1')
    })
    await context.step('failed committed drain restores fresh running instances', async () => {
      await replace('ReloadFixture-2.jar')
      await writeFile(path.join(data, 'fail-commit'), '')
      try {
        await reload(/Restored running version of DependentFixture/)
        await state('ReloadFixture', '2', 'prepare=0 commit=0')
        await state('DependentFixture', '1', 'prepare=0 commit=0')
      } finally {
        await rm(path.join(data, 'fail-commit'), { force: true })
      }
    })
    await context.step('retired fixture classloaders become collectible', async () => {
      await context.waitUntil(async () => {
        const response = await context.command('/reloadfixture:fixturestate gc', /FIXTURE name=ReloadFixture/, 10000)
        return String(response).includes('liveLoaders=1')
      }, { timeoutMs: 30000, intervalMs: 1000, label: 'only current root classloader remains' })
      await state('DependentFixture', '1', 'liveLoaders=1')
      context.report.retiredClassloadersCollected = true
    })
    await context.step('failed disable reports failure and restores the running group', async () => {
      await writeFile(path.join(data, 'fail-disable'), '')
      try {
        const output = await reload(/Restored running version of DependentFixture/)
        context.expect(output.includes('Incomplete teardown'), 'Disable failure must be reported')
        await state('ReloadFixture', '2')
        await state('DependentFixture', '1')
      } finally {
        await rm(path.join(data, 'fail-disable'), { force: true })
      }
    })
    await context.step('self reload preserves dependency recovery and command availability', async () => {
      const configuration = path.join(plugins, 'BileTools', 'biletools.yml')
      await writeFile(configuration, (await readFile(configuration, 'utf8')).replace('NeverAutomaticFixture', 'ReloadFixture'))
      const offset = (await readFile(log, 'utf8')).length
      context.bot.chat('/bile reload BileTools')
      await context.waitUntil(async () => /Timing reload BileTools:.*health=ok/.test((await readFile(log, 'utf8')).slice(offset)),
        { timeoutMs: 60000, intervalMs: 100, label: 'BileTools self reload' })
      await state('ReloadFixture', '2')
      await state('DependentFixture', '1')
      await context.command('/bile inspect ReloadFixture', /recovery=true.*cooperative=true.*DependentFixture/, 10000)
      await reload(/Timing reload ReloadFixture:.*health=ok/)
    })
    await context.step('watcher replaces and recovers, then manual repair resumes automation', async () => {
      for (const [artifact, expectedVersion, expectedOutcome] of [
        ['ReloadFixture-1.jar', '1', /Timing reload ReloadFixture:.*health=ok/],
        ['ReloadFixture-broken.jar', '1', /Restored running version of DependentFixture/]
      ]) {
        const offset = (await readFile(log, 'utf8')).length
        await replace(artifact)
        await context.waitUntil(async () => expectedOutcome.test((await readFile(log, 'utf8')).slice(offset)),
          { timeoutMs: 45000, intervalMs: 200, label: `automatic deployment ${artifact}` })
        await state('ReloadFixture', expectedVersion)
        await state('DependentFixture', '1')
      }
      await replace('ReloadFixture-2.jar')
      await reload(/Timing reload ReloadFixture:.*health=ok/)
      await state('ReloadFixture', '2')
      const offset = (await readFile(log, 'utf8')).length
      await replace('ReloadFixture-1.jar')
      await context.waitUntil(async () => /Timing reload ReloadFixture:.*health=ok/.test((await readFile(log, 'utf8')).slice(offset)),
        { timeoutMs: 45000, intervalMs: 200, label: 'automatic deployment after manual repair' })
      await state('ReloadFixture', '1')
    })
    await context.step('replacement loads a newly installed required dependency from its prepared copy', async () => {
      await copyFile(path.join(artifacts, 'MissingFixture-1.jar'), path.join(plugins, 'MissingFixture.jar'))
      await replace('ReloadFixture-missing.jar')
      await reload(/Timing reload ReloadFixture:.*health=ok/)
      await state('ReloadFixture', '4')
      await state('MissingFixture', '1')
      await context.command('/bile inspect MissingFixture', /recovery=true.*cooperative=true.*ReloadFixture/, 10000)
    })
    await context.step('deleted source still unloads and preserves the unrelated shared command', async () => {
      const offset = (await readFile(log, 'utf8')).length
      await rm(path.join(plugins, 'ReloadFixture.jar'))
      await context.waitUntil(async () => /Timing unload ReloadFixture:/.test((await readFile(log, 'utf8')).slice(offset)),
        { timeoutMs: 30000, intervalMs: 100, label: 'dependency group unload' })
      await context.command('/sharedprobe', /FIXTURE name=CollisionOwner version=1/, 10000)
      await context.command('/bile inspect ReloadFixture', /not find|not found|isn't|does not exist|Couldn't find/i, 10000)
    })
  }
}
