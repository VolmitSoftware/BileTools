import { copyFile, mkdir, rm, writeFile } from 'node:fs/promises'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

export default {
  name: 'velocity-reload-recovery',
  description: 'Verify proxy reload recovery, cooperative veto, channels, and retired classloaders with a connected client.',
  async run(context) {
    const directory = context.server.directory
    const fixtures = fileURLToPath(new URL('../../../build/qa/', import.meta.url))
    const source = path.join(directory, 'plugins', 'qacore.jar')
    const veto = path.join(directory, 'plugins', 'qacore', 'veto')
    const command = async (text, expected) => {
      await context.sleep(150)
      return context.command(text, expected, 20000)
    }
    const checkPlugins = async revision => {
      await command('/qaprobe', new RegExp(`probe=${revision} prepared=false`))
      await command('/qaaddon', /addon-ready=true/)
      await command('/qactl packets', /packet-mappings=1/)
      context.expect(context.bot.entity !== undefined, 'The player must remain connected through reloads')
    }
    await context.step('startup proxy commands and dependency', async () => {
      await checkPlugins('original')
      await command('/bile inspect qacore', /Recovery copy: available/)
    })
    await context.step('cooperative veto preserves and resumes the dependency group', async () => {
      await mkdir(path.dirname(veto), { recursive: true })
      await writeFile(veto, 'veto')
      try {
        await command('/bile reload qacore', /refused unloading: fixture veto/)
        await checkPlugins('original')
      } finally {
        await rm(veto, { force: true })
      }
    })
    await context.step('timed-out preparation remains quarantined until it finishes', async () => {
      const slow = path.join(directory, 'plugins', 'qacore', 'slow')
      await writeFile(slow, 'slow')
      try {
        await command('/bile reload qacore', /TimeoutException/)
        await command('/bile unload qacore', /still running after its timeout/)
        await command('/bile inspect qacore', /timed out, still running/)
        await context.waitUntil(async () => {
          const response = await command('/qaprobe', /probe=original prepared=/)
          return response.includes('prepared=false')
        }, { timeoutMs: 12000, intervalMs: 300, label: 'cancelled preparation recovery' })
        await checkPlugins('original')
      } finally {
        await rm(slow, { force: true })
      }
    })
    await context.step('broken replacement restores original startup bytes and dependents', async () => {
      await command('/qactl track', /tracked=2/)
      await copyFile(path.join(fixtures, 'qaCoreBroken.jar'), source)
      await command('/bile reload qacore', /previous dependency group restored/)
      await checkPlugins('original')
    })
    await context.step('repeated successful replacement with a connected player', async () => {
      await copyFile(path.join(fixtures, 'qaCoreReplacement.jar'), source)
      for (let index = 0; index < 3; index++) {
        await command('/qactl track', /tracked=/)
        await command('/bile reload qacore', /Reloaded qacore/)
        await checkPlugins('replacement')
      }
    })
    await context.step('retired plugin classloaders become collectible', async () => {
      await context.waitUntil(async () => {
        const response = await command('/qactl gc', /retired-alive=/)
        return /retired-alive=0 /.test(response)
      }, { timeoutMs: 20000, intervalMs: 300, label: 'retired classloader collection' })
    })
    await context.step('owned channels leave while unrelated registrations survive', async () => {
      await command('/qactl takeover', /collision-taken/)
      await command('/bile unload qacore', /Unloaded qacore and 1 dependent plugins/)
      await command('/qacollision', /collision=observer/)
      await command('/qactl packets', /packet-mappings=0/)
      const response = await command('/qactl state', /channels=/)
      context.expect(response.includes('qa:external'), 'The external channel must remain registered')
      context.expect(!response.includes('qa:core') && !response.includes('qa:addon') && !response.includes('qa:shared'),
        'All exclusively tracked channels must be removed after the last owner leaves')
      await command('/bile load qacore.jar', /Loaded qacore/)
      await command('/bile load qaaddon.jar', /Loaded qaaddon/)
      await checkPlugins('replacement')
    })
  }
}
