import { readFile } from 'node:fs/promises'
import path from 'node:path'
import glossInteraction from '../../../../Gloss/src/test/gameplay/public-interaction.mjs'

export default {
  name: 'bile-gloss-hotload',
  description: 'Reload Gloss three times, reconnect after its packet library closes existing connections, and verify public UI actions after every reload.',
  async run(context) {
    context.report.hotloadCycles = []
    let active = context
    await context.step('verify the installed plugin before hotload', () => glossInteraction.run(context))
    for (let cycle = 1; cycle <= 3; cycle += 1) {
      await context.step(`hotload Gloss and its owned library, cycle ${cycle}`, async () => {
        const started = performance.now()
        const logPath = path.join(context.server.directory, 'logs', 'latest.log')
        const offset = (await readFile(logPath, 'utf8')).length
        let completion
        active = await active.reconnectAfter(async () => {
          active.bot.chat('/bile reload Gloss')
          completion = await context.waitUntil(async () => {
            const appended = (await readFile(logPath, 'utf8')).slice(offset)
            context.expect(!/ConcurrentModificationException|Failed to reload|Post-reload health check failed/.test(appended), 'Reload logged a lifecycle failure', { appended })
            return appended.match(/Timing reload Gloss:[^\r\n]*health=ok/)?.[0]
          }, { timeoutMs: 60000, intervalMs: 100, label: 'server completes Gloss reload' })
        }, { timeoutMs: 60000 })
        await glossInteraction.run(active)
        context.report.hotloadCycles.push({ cycle, durationMs: Math.round(performance.now() - started), username: active.bot.username, reconnected: true, completion, ui: context.report.ui })
      })
    }
    context.expect(context.report.hotloadCycles.length === 3, 'All three hotloads must preserve command and UI behavior')
  }
}
