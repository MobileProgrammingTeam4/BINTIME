#!/usr/bin/env python3
"""Bootstrap GitHub Projects for BIN TIME. Dry run unless --apply."""
import argparse
import json
import subprocess
import sys

def gh(*args):
    result = subprocess.run(["gh", *map(str,args)], capture_output=True, text=True)
    if result.returncode:
        raise RuntimeError(result.stderr.strip() or "GitHub CLI failed")
    return result.stdout

def api(*args):
    return json.loads(gh(*args))

def main():
    p=argparse.ArgumentParser()
    p.add_argument("--apply",action="store_true")
    p.add_argument("--owner",default="MobileProgrammingTeam4")
    p.add_argument("--repo",default="MobileProgrammingTeam4/BINTIME")
    p.add_argument("--title",default="BIN TIME - Development Board")
    args=p.parse_args()
    issues=api("issue","list","--repo",args.repo,"--state","open","--limit","200","--json","number,title,url")
    issues=[x for x in issues if x["title"].startswith("[BT-")]
    if len(issues)!=61:
        sys.exit("Expected 61 open BT issues; found "+str(len(issues))+". No changes made.")
    all_projects=api("project","list","--owner",args.owner,"--limit","100","--format","json")["projects"]
    matched=[x for x in all_projects if x.get("title")==args.title]
    if len(matched)>1:
        sys.exit("Multiple projects share this title. No changes made.")
    existing=matched[0] if matched else None
    print("Issues:",len(issues),"Existing Project:",existing and existing["number"])
    if not args.apply:
        print("Dry run. Re-run with --apply to create/link/import.")
        return
    number=existing["number"] if existing else api("project","create","--owner",args.owner,"--title",args.title,"--format","json")["number"]
    gh("project","link",str(number),"--owner",args.owner,"--repo",args.repo)
    fields=api("project","field-list",str(number),"--owner",args.owner,"--format","json")["fields"]
    names={x["name"] for x in fields}
    for name,options in [
        ("Sprint","Sprint 1,Sprint 2,Sprint 3,Sprint 4,Sprint 5"),
        ("Priority","P0,P1,P2"),
        ("Team","A,B,C,D,E"),
    ]:
        if name not in names:
            gh("project","field-create",str(number),"--owner",args.owner,"--name",name,"--data-type","SINGLE_SELECT","--single-select-options",options)
    existing_items=api("project","item-list",str(number),"--owner",args.owner,"--limit","200","--format","json")["items"]
    present={x.get("content",{}).get("url") for x in existing_items}
    added=0
    for issue in issues:
        if issue["url"] in present: continue
        gh("project","item-add",str(number),"--owner",args.owner,"--url",issue["url"])
        added+=1
    print("Project",number,"imported",added,"issues.")
    print("Next: add In Review to Project Status options, configure repository secret/variables (see docs).")

if __name__=="__main__":
    try: main()
    except (RuntimeError,ValueError,KeyError) as e:
        sys.exit("GitHub CLI error: "+str(e))
