// 从 WebPolyfill.kt 里抽取某个 Kotlin 原样字符串常量（单一真源：测试绝不手抄一份源码）。
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
export const KOTLIN = path.join(HERE, '..', '..', 'app/src/main/java/com/aptuidsh/kui/WebPolyfill.kt');

/** @returns 常量 `"""…"""` 的内容（不含定界符）。 */
export function extract(kotlinName) {
  const kt = fs.readFileSync(KOTLIN, 'utf8');
  const marker = `private const val ${kotlinName} = """`;
  const i = kt.indexOf(marker);
  if (i < 0) throw new Error(`WebPolyfill.kt 里找不到 ${kotlinName}`);
  const start = i + marker.length;
  const end = kt.indexOf('\n"""', start);
  if (end < 0) throw new Error(`${kotlinName} 的结束定界符缺失`);
  return kt.slice(start, end + 1);
}
