function escapeHtml(value: string) {
  return value
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#039;')
}

function inlineMarkdown(value: string) {
  const codeFragments: string[] = []
  let result = escapeHtml(value).replace(/`([^`]+)`/g, (_match: string, code: string) => {
    const index = codeFragments.push(`<code>${code}</code>`) - 1
    return `\u0000CODE${index}\u0000`
  })

  result = result
    .replace(/\[([^\]]+)\]\((https?:\/\/[^)\s]+|\/[^)\s]*)\)/g, '<a href="$2">$1</a>')
    .replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
    .replace(/(^|[^\w])\*([^*\n]+)\*/g, '$1<em>$2</em>')
    .replace(/\u0000CODE(\d+)\u0000/g, (_match: string, index: string) => codeFragments[Number(index)] ?? '')

  return result
}

function headingId(value: string, index: number) {
  const normalized = value
    .replace(/[*_`[\]()]/g, '')
    .trim()
    .replace(/\s+/g, '-')
    .replace(/[^\p{Letter}\p{Number}\-]/gu, '')
  return normalized || `section-${index}`
}

function isBlockStart(line: string, nextLine = '') {
  return /^(#{1,4})\s+/.test(line)
    || /^>\s?/.test(line)
    || /^[-*]\s+/.test(line)
    || /^\d+\.\s+/.test(line)
    || /^```/.test(line)
    || (/^\|.+\|$/.test(line) && /^\|?[\s:|-]+\|?$/.test(nextLine))
}

export function renderMarkdown(markdown: string) {
  const lines = markdown.replace(/\r\n?/g, '\n').split('\n')
  const html: string[] = []
  let index = 0
  let headingIndex = 0

  while (index < lines.length) {
    const line = lines[index].trim()
    if (!line) {
      index += 1
      continue
    }

    const heading = line.match(/^(#{1,4})\s+(.+)$/)
    if (heading) {
      const level = heading[1].length
      const title = heading[2].trim()
      html.push(`<h${level} id="${headingId(title, headingIndex++)}">${inlineMarkdown(title)}</h${level}>`)
      index += 1
      continue
    }

    if (line.startsWith('```')) {
      const code: string[] = []
      index += 1
      while (index < lines.length && !lines[index].trim().startsWith('```')) {
        code.push(lines[index])
        index += 1
      }
      html.push(`<pre><code>${escapeHtml(code.join('\n'))}</code></pre>`)
      index += 1
      continue
    }

    if (line.startsWith('>')) {
      const quote: string[] = []
      while (index < lines.length && lines[index].trim().startsWith('>')) {
        quote.push(lines[index].trim().replace(/^>\s?/, ''))
        index += 1
      }
      html.push(`<blockquote>${quote.map(inlineMarkdown).join('<br>')}</blockquote>`)
      continue
    }

    const unordered = /^[-*]\s+/.test(line)
    const ordered = /^\d+\.\s+/.test(line)
    if (unordered || ordered) {
      const tag = ordered ? 'ol' : 'ul'
      const matcher = ordered ? /^\d+\.\s+/ : /^[-*]\s+/
      const items: string[] = []
      while (index < lines.length && matcher.test(lines[index].trim())) {
        items.push(`<li>${inlineMarkdown(lines[index].trim().replace(matcher, ''))}</li>`)
        index += 1
      }
      html.push(`<${tag}>${items.join('')}</${tag}>`)
      continue
    }

    const nextLine = lines[index + 1]?.trim() ?? ''
    if (/^\|.+\|$/.test(line) && /^\|?[\s:|-]+\|?$/.test(nextLine)) {
      const rows: string[][] = []
      const splitRow = (row: string) => row.slice(1, -1).split('|').map((cell) => cell.trim())
      const headers = splitRow(line)
      index += 2
      while (index < lines.length && /^\|.+\|$/.test(lines[index].trim())) {
        rows.push(splitRow(lines[index].trim()))
        index += 1
      }
      html.push(
        `<div class="help-table-wrap"><table><thead><tr>${headers.map((cell) => `<th>${inlineMarkdown(cell)}</th>`).join('')}</tr></thead>`
        + `<tbody>${rows.map((row) => `<tr>${row.map((cell) => `<td>${inlineMarkdown(cell)}</td>`).join('')}</tr>`).join('')}</tbody></table></div>`,
      )
      continue
    }

    const paragraph = [line]
    index += 1
    while (index < lines.length) {
      const current = lines[index].trim()
      const following = lines[index + 1]?.trim() ?? ''
      if (!current || isBlockStart(current, following)) break
      paragraph.push(current)
      index += 1
    }
    html.push(`<p>${inlineMarkdown(paragraph.join(' '))}</p>`)
  }

  return html.join('\n')
}
