import { test as base, expect } from '@playwright/test'

base.setTimeout(240_000)

export const test = base
export { expect }
