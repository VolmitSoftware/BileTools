import { copyFile, readFile, rename } from 'node:fs/promises'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const fixtures = fileURLToPath(new URL('../runtime/native-paper/build/libs/', import.meta.url))

export default {
  name: 'bile-native-paper',
  description: 'Verify native Paper bootstrap commands, classpath loading, repeated reloads and previous-version recovery with a connected player.',
  async run(context) {
    context.bot.physicsEnabled = false
    const source = await readFile(path.join(context.server.directory, '.server-source'), 'utf8')
    context.expect(source.includes('isolated=true'), 'Native lifecycle scenarios require an isolated instance')
    const pluginJar = path.join(context.server.directory, 'plugins', 'NativePaperFixture.jar')
    const logPath = path.join(context.server.directory, 'logs', 'latest.log')
    context.report.nativePaper = []

    const deploy = async version => {
      await copyFile(path.join(fixtures, `NativePaperFixture-${version}.jar`), `${pluginJar}.pending`)
      await rename(`${pluginJar}.pending`, pluginJar)
    }
    const operation = async (command, expected, { allowFailure = false } = {}) => {
      const offset = (await readFile(logPath, 'utf8')).length
      context.bot.chat(command)
      return context.waitUntil(async () => {
        const appended = (await readFile(logPath, 'utf8')).slice(offset)
        if (!allowFailure) {
          context.expect(!/Native Paper load failed|Native Paper enable failed|Experimental native Paper runtime loading is unavailable|Could not restore/.test(appended),
            'Native lifecycle operation failed', { appended })
        }
        return expected.test(appended) ? appended : false
      }, { timeoutMs: 30000, intervalMs: 100, label: command })
    }
    const verify = async version => {
      const pattern = new RegExp(`NATIVE_FIXTURE version=${version} mode=good bootstrap=1 load=1 enable=1 disable=0 bootstrapCommands=1 pluginCommands=1 library=classpath-ok`)
      const bootstrap = await context.command('/nativefixture', pattern, 10000)
      const plugin = await context.command('/nativefixtureplugin', pattern, 10000)
      context.report.nativePaper.push({ version, bootstrap, plugin })
    }

    if (/Loading server plugin NativePaperFixture/.test(await readFile(logPath, 'utf8'))) {
      await context.step('reload a native plugin originally loaded during server startup', async () => {
        await operation('/bile reload NativePaperFixture', /Timing reload NativePaperFixture:.*health=ok/)
        await context.command('/nativefixture', /bootstrap=1 load=1 enable=1 disable=0 bootstrapCommands=1 pluginCommands=1 library=classpath-ok/, 10000)
      })
      await context.step('unload a native plugin originally loaded during server startup', async () => {
        await operation('/bile unload NativePaperFixture', /Timing unload NativePaperFixture:/)
      })
    }
    await context.step('hotload a native Paper-only plugin with a custom classpath', async () => {
      await deploy('1')
      await operation('/bile load NativePaperFixture', /Enabled NativePaperFixture successfully/)
      await verify('1')
    })
    for (let cycle = 1; cycle <= 3; cycle++) {
      await context.step(`reload native bootstrap and plugin commands, cycle ${cycle}`, async () => {
        await operation('/bile reload NativePaperFixture', /Timing reload NativePaperFixture:.*health=ok/)
        await verify('1')
      })
    }
    await context.step('replace the native plugin with a new working version', async () => {
      await deploy('2')
      await operation('/bile reload NativePaperFixture', /Timing reload NativePaperFixture:.*health=ok/)
      await verify('2')
    })
    for (const version of ['bad-load', 'bad-enable', 'startup-only']) {
      await context.step(`recover the previous native plugin after ${version}`, async () => {
        await deploy(version)
        const output = await operation('/bile reload NativePaperFixture', /Restored running version of NativePaperFixture/, { allowFailure: true })
        context.expect(!output.includes('Could not restore'), 'Previous-version restoration failed', { output })
        await verify('2')
      })
    }
    await context.step('remove both native command registrations when unloaded', async () => {
      await deploy('2')
      await operation('/bile unload NativePaperFixture', /Timing unload NativePaperFixture:/)
      await context.command('/nativefixture', /Unknown or incomplete command|Unknown command/i, 10000)
      await context.command('/nativefixtureplugin', /Unknown or incomplete command|Unknown command/i, 10000)
    })
    await context.step('load again after complete native teardown', async () => {
      await operation('/bile load NativePaperFixture', /Enabled NativePaperFixture successfully/)
      await verify('2')
    })
    await context.step('collect every retired native plugin classloader', async () => {
      const status = await context.waitUntil(async () => {
        const response = await context.command('/nativefixture gc', /NATIVE_FIXTURE .*liveLoaders=\d+/, 5000)
        context.report.nativeLoaderCollection = response
        return /liveLoaders=1(?:\s|$)/.test(response) ? response : false
      }, { timeoutMs: 15000, intervalMs: 250, label: 'only the active native classloader remains reachable' })
      context.expect(status.includes('liveLoaders=1'), 'Retired native classloaders remain reachable')
    })
  }
}
