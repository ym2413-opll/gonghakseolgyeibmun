#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Excel Course Data Converter
Reads '2026학년도 2학기 개설과목 및 강의시간표 (폐강정정기간).xlsx' and converts it into
a structured JSON file for runtime backend loading and database seeding.
"""

import sys
import os
import json
import uuid
import datetime
import openpyxl

EXCEL_FILE = os.path.join(os.path.dirname(os.path.dirname(__file__)), '2026학년도 2학기 개설과목 및 강의시간표 (폐강정정기간).xlsx')
BACKEND_RESOURCE_DIR = os.path.join(os.path.dirname(os.path.dirname(__file__)), 'proj', 'backend', 'src', 'main', 'resources')
FRONTEND_ASSET_DIR = os.path.join(os.path.dirname(os.path.dirname(__file__)), 'proj', 'frontend', 'src', 'assets')

DEMO_COURSE_1_ID = '33333333-3333-3333-3333-333333333301' # 공학설계입문 (전자정보공과대학 7000-1-2957)
DEMO_COURSE_2_ID = '33333333-3333-3333-3333-333333333302' # 자료구조 (소프트웨어학부 I030-2-1243)

def generate_deterministic_uuid(namespace_prefix, key):
    return str(uuid.uuid5(uuid.NAMESPACE_DNS, f"{namespace_prefix}:{key}"))

def convert_excel_to_json():
    if not os.path.exists(EXCEL_FILE):
        print(f"Error: Excel file not found at {EXCEL_FILE}", file=sys.stderr)
        sys.exit(1)

    print(f"Loading Excel file: {EXCEL_FILE} ...")
    wb = openpyxl.load_workbook(EXCEL_FILE, read_only=True, data_only=True)
    sheet = wb.active
    rows = list(sheet.iter_rows(values_only=True))

    courses_map = {}
    professors_map = {}
    total_sections = 0

    # Header is at row index 2 (1-based row 3). Data starts at row index 4.
    for r in rows[4:]:
        full_code = str(r[6]).strip() if r[6] else ''
        name = str(r[7]).strip() if r[7] else ''
        if not full_code or not name:
            continue

        parts = full_code.rsplit('-', 1)
        base_code = parts[0]
        sec = parts[1] if len(parts) > 1 else '01'

        college = str(r[2]).strip() if r[2] else ''
        dept = str(r[3]).strip() if r[3] else ''
        major = str(r[4]).strip() if r[4] else ''
        general_edu = str(r[5]).strip() if r[5] else ''
        comp_type = str(r[17]).strip() if r[17] else ''

        try:
            credit = int(r[19]) if r[19] is not None and str(r[19]).isdigit() else 3
        except (ValueError, TypeError):
            credit = 3

        try:
            hours = int(r[20]) if r[20] is not None and str(r[20]).isdigit() else 3
        except (ValueError, TypeError):
            hours = 3

        prof_name = str(r[21]).strip() if r[21] else '미지정'
        capacity = int(r[22]) if r[22] is not None and str(r[22]).isdigit() else 0
        remark = str(r[33]).strip() if r[33] else ''

        # Schedule parsing
        sched_items = []
        if r[24] and r[25]:
            room1 = str(r[26]).strip() if r[26] else ''
            sched_items.append(f"{r[24]} {r[25]}교시" + (f"({room1})" if room1 else ""))
        if r[27] and r[28]:
            room2 = str(r[29]).strip() if r[29] else ''
            sched_items.append(f"{r[27]} {r[28]}교시" + (f"({room2})" if room2 else ""))
        if r[30] and r[31]:
            room3 = str(r[32]).strip() if r[32] else ''
            sched_items.append(f"{r[30]} {r[31]}교시" + (f"({room3})" if room3 else ""))
        schedule_str = ', '.join(sched_items)

        # Professor record
        prof_id = None
        if prof_name and prof_name != '미지정':
            if prof_name not in professors_map:
                prof_uuid = generate_deterministic_uuid("prof", prof_name)
                prof_dept = dept if dept and dept != '공통' else (college if college else '공통')
                identifier = f"P_{len(professors_map) + 1:04d}"
                professors_map[prof_name] = {
                    "id": prof_uuid,
                    "name": prof_name,
                    "department": prof_dept,
                    "identifier": identifier
                }
            elif professors_map[prof_name]["department"] in ("공통", "") and dept not in ("공통", ""):
                professors_map[prof_name]["department"] = dept

            prof_id = professors_map[prof_name]["id"]

        # Course record
        if base_code not in courses_map:
            if base_code == '7000-1-2957':
                course_id = DEMO_COURSE_1_ID
            elif base_code == 'I030-2-1243':
                course_id = DEMO_COURSE_2_ID
            else:
                course_id = generate_deterministic_uuid("course", base_code)

            courses_map[base_code] = {
                "id": course_id,
                "name": name,
                "code": base_code,
                "semester": "2026-2",
                "college": college,
                "department": dept,
                "major": major,
                "generalEduType": general_edu,
                "completionType": comp_type,
                "credit": credit,
                "hours": hours,
                "sections": []
            }

        courses_map[base_code]["sections"].append({
            "section": f"{sec}분반",
            "sectionCode": sec,
            "fullCode": full_code,
            "professorName": prof_name,
            "professorId": prof_id,
            "schedule": schedule_str,
            "capacity": capacity,
            "remark": remark
        })
        total_sections += 1

    sorted_professors = sorted(professors_map.values(), key=lambda p: p["name"])
    sorted_courses = sorted(courses_map.values(), key=lambda c: (c["college"], c["department"], c["name"]))

    output_data = {
        "metadata": {
            "title": "2026학년도 2학기 개설과목 및 강의시간표",
            "semester": "2026-2",
            "generatedAt": datetime.datetime.now().isoformat(),
            "totalCourses": len(sorted_courses),
            "totalSections": total_sections,
            "totalProfessors": len(sorted_professors)
        },
        "professors": sorted_professors,
        "courses": sorted_courses
    }

    # Save to backend resources
    os.makedirs(BACKEND_RESOURCE_DIR, exist_ok=True)
    backend_out_file = os.path.join(BACKEND_RESOURCE_DIR, 'courses_data.json')
    with open(backend_out_file, 'w', encoding='utf-8') as f:
        json.dump(output_data, f, ensure_ascii=False, indent=2)
    print(f"Backend data saved successfully -> {backend_out_file}")

    # Save to frontend assets
    os.makedirs(FRONTEND_ASSET_DIR, exist_ok=True)
    frontend_out_file = os.path.join(FRONTEND_ASSET_DIR, 'courses_data.json')
    with open(frontend_out_file, 'w', encoding='utf-8') as f:
        json.dump(output_data, f, ensure_ascii=False, indent=2)
    print(f"Frontend data saved successfully -> {frontend_out_file}")

    print("\n[Summary]")
    print(f"- Total unique courses: {len(sorted_courses)}")
    print(f"- Total sections: {total_sections}")
    print(f"- Total unique professors: {len(sorted_professors)}")
    print(f"- Demo course 1 (공학설계입문 7000-1-2957): {DEMO_COURSE_1_ID}")
    print(f"- Demo course 2 (자료구조 I030-2-1243): {DEMO_COURSE_2_ID}")

if __name__ == '__main__':
    convert_excel_to_json()
