import { Resvg } from '@resvg/resvg-js';
import { readFileSync, writeFileSync } from 'node:fs';

const xml = readFileSync('/workspace/QuantApp/app/src/main/res/drawable/ic_launcher_foreground.xml', 'utf8');
const m = xml.match(/android:pathData="([^"]+)"/);
const full = m[1];
const firstZ = full.indexOf(' z');
const bull = full.slice(0, firstZ + 2);

// 计算包围盒
const nums = bull.match(/-?\d+\.?\d*/g).map(Number);
let xs = [], ys = [];
for (let i = 0; i < nums.length; i += 2) { xs.push(nums[i]); ys.push(nums[i+1]); }
const minX = Math.min(...xs), maxX = Math.max(...xs);
const minY = Math.min(...ys), maxY = Math.max(...ys);
console.log('bbox', minX, maxX, minY, maxY, 'w', maxX-minX, 'h', maxY-minY);

// 缩放居中到 400x400
const svg = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 512 512" width="1024" height="1024">
<rect width="512" height="512" fill="#FF0000"/>
<g transform="translate(46,46)">
  <g transform="scale(${380/(maxX-minX)},${380/(maxY-minY)}) translate(${-minX},${-minY})">
    <path fill="#FFFFFF" d="${bull}"/>
  </g>
</g>
</svg>`;
const png = new Resvg(svg).render().asPng();
writeFileSync('/workspace/QuantApp/tools/bull_body.png', png);
console.log('done', png.length);