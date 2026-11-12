export interface SelectOption { value: string; label: string; description?: string; disabled?: boolean }

/** Move through enabled options, wrapping at either edge. Empty lists have no active item. */
export function moveOption(options: readonly SelectOption[], current: number, direction: 1 | -1): number {
  for (let offset = 1; offset <= options.length; offset++) {
    const index = ((current + direction * offset) % options.length + options.length) % options.length;
    if (!options[index]?.disabled) return index;
  }
  return -1;
}

export function edgeOption(options: readonly SelectOption[], last = false): number {
  return moveOption(options, last ? 0 : -1, last ? -1 : 1);
}

export function popupPlacement(rect: { left: number; top: number; bottom: number; width: number }, width: number, height: number, desiredHeight = 320) {
  const margin = 8, gap = 6;
  const below = Math.max(0, height - rect.bottom - margin - gap);
  const above = Math.max(0, rect.top - margin - gap);
  const flip = below < desiredHeight && above > below;
  const maxHeight = Math.min(desiredHeight, flip ? above : below);
  const popupWidth = Math.min(Math.max(rect.width, 180), Math.max(0, width - margin * 2));
  return { left: Math.max(margin, Math.min(rect.left, width - popupWidth - margin)), top: flip ? rect.top - gap : rect.bottom + gap, width: popupWidth, maxHeight, flip };
}
