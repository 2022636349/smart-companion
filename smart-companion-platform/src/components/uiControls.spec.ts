import { describe, expect, it } from 'vitest';
import { edgeOption, moveOption, popupPlacement } from './uiSelectLogic';

describe('select keyboard navigation', () => {
  const options = [{ value: 'a', label: '甲', disabled: true }, { value: 'b', label: '乙' }, { value: 'c', label: '丙', disabled: true }, { value: 'd', label: '丁' }];
  it('skips disabled options and wraps in either direction', () => {
    expect(moveOption(options, 1, 1)).toBe(3);
    expect(moveOption(options, 3, 1)).toBe(1);
    expect(moveOption(options, 1, -1)).toBe(3);
  });
  it('Home and End locate the enabled edges', () => {
    expect(edgeOption(options)).toBe(1);
    expect(edgeOption(options, true)).toBe(3);
  });
  it('has no active descendant for empty or wholly disabled lists', () => {
    expect(edgeOption([])).toBe(-1);
    expect(edgeOption(options.map(option => ({ ...option, disabled: true })))).toBe(-1);
  });
});

describe('teleported popup bounds', () => {
  it('opens beneath the trigger when there is space', () => {
    const position = popupPlacement({ left: 30, top: 50, bottom: 92, width: 240 }, 1000, 700);
    expect(position).toEqual({ left: 30, top: 98, width: 240, maxHeight: 320, flip: false });
  });
  it('anchors above short or tall lists near the bottom and clamps the right edge', () => {
    const position = popupPlacement({ left: 320, top: 500, bottom: 542, width: 200 }, 390, 600);
    expect(position.flip).toBe(true);
    expect(position.top).toBe(494);
    expect(position.left + position.width).toBeLessThanOrEqual(382);
    expect(position.maxHeight).toBeLessThanOrEqual(position.top - 8);
  });
  it('keeps an oversized trigger within a narrow viewport', () => {
    const position = popupPlacement({ left: -15, top: 100, bottom: 140, width: 700 }, 320, 240);
    expect(position.left).toBe(8);
    expect(position.width).toBe(304);
    expect(position.maxHeight).toBeGreaterThanOrEqual(0);
  });
});
