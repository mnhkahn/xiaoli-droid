#!/usr/bin/env python3
import argparse
import hashlib
import json
from pathlib import Path

RELEASE_NOTES = '长按消息可选飞书表情、引用回复、Pin、本机置顶或添加任务。作业卡片按飞书和 Lark 的样式展示摘要和分页表格，点省略的单元格可查看全文。'

def make_manifest(apk, version, code, tag, pgyer):
    return {'versionName':version,'versionCode':int(code),'apkUrl':f'https://github.com/mnhkahn/xiaoli-droid/releases/download/{tag}/xiaoli.apk','sha256':hashlib.sha256(apk.read_bytes()).hexdigest(),'pgyerUrl':pgyer,'notes':RELEASE_NOTES}

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('apk',type=Path);p.add_argument('--version',required=True);p.add_argument('--code',required=True,type=int);p.add_argument('--tag',required=True);p.add_argument('--pgyer',default='');p.add_argument('--output',type=Path,default=Path('update.json'));a=p.parse_args();a.output.write_text(json.dumps(make_manifest(a.apk,a.version,a.code,a.tag,a.pgyer),ensure_ascii=False,indent=2)+'\n')
