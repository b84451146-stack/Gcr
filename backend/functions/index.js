"use strict";
/**
 * GCR Eco backend (Google Classroom add-on API).
 *
 * Teachers NEVER read Firestore directly. Every request carries the user's own
 * Google OAuth access token; we check it with Google, then ask the official
 * Classroom API whether that person is a teacher of the course. Only then do we
 * use the Admin SDK to build the report for that course's roster.
 */
const { onRequest } = require("firebase-functions/v2/https");
const admin = require("firebase-admin");
const express = require("express");

admin.initializeApp();
const db = admin.firestore();
const app = express();
app.use(express.json());

const CLIENT_ID = process.env.CLIENT_ID;
const CLASSROOM = "https://classroom.googleapis.com/v1";
const ITEM_SEGMENTS = {
  courseWork: "courseWork",
  courseWorkMaterial: "courseWorkMaterials",
  announcement: "announcements",
};

class HttpError extends Error {
  constructor(status, message) {
    super(message);
    this.status = status;
  }
}
const wrap = (fn) => (req, res) =>
  fn(req, res).catch((e) => {
    const status = e.status || 500;
    if (status === 500) console.error(e);
    res.status(status).json({ error: status === 500 ? "Server error" : e.message });
  });
const enc = encodeURIComponent;

// ---- auth -----------------------------------------------------------------
async function verifyGoogleToken(req) {
  const m = /^Bearer (.+)$/.exec(req.get("authorization") || "");
  if (!m) throw new HttpError(401, "Missing token");
  if (!CLIENT_ID) throw new HttpError(500, "Server missing CLIENT_ID");
  const r = await fetch(`https://oauth2.googleapis.com/tokeninfo?access_token=${enc(m[1])}`);
  if (!r.ok) throw new HttpError(401, "Invalid or expired Google token");
  const info = await r.json();
  if (info.aud !== CLIENT_ID && info.azp !== CLIENT_ID) {
    throw new HttpError(401, "Token was not issued for this app");
  }
  req.token = m[1];
  req.googleEmail = (info.email || "").toLowerCase();
  return req;
}

async function classroomGet(token, path) {
  const r = await fetch(`${CLASSROOM}${path}`, { headers: { Authorization: `Bearer ${token}` } });
  const data = await r.json().catch(() => ({}));
  if (!r.ok) throw new HttpError(r.status === 404 ? 403 : r.status, data.error?.message || "Classroom API error");
  return data;
}

// Throws unless the token's owner is a teacher of this course.
async function requireTeacher(token, courseId) {
  await classroomGet(token, `/courses/${enc(courseId)}/teachers/me`);
}

async function listStudents(token, courseId) {
  const out = [];
  let pageToken = "";
  do {
    const q = `?pageSize=100${pageToken ? `&pageToken=${enc(pageToken)}` : ""}`;
    const data = await classroomGet(token, `/courses/${enc(courseId)}/students${q}`);
    out.push(...(data.students || []));
    pageToken = data.nextPageToken || "";
  } while (pageToken);
  return out;
}

// ---- data -----------------------------------------------------------------
const sinceDate = () => new Date(Date.now() - 6 * 86400000).toISOString().slice(0, 10);

async function usageForUid(uid, since) {
  const snap = await db.collection("users").doc(uid).collection("usage").where("date", ">=", since).get();
  const byApp = {};
  const daily = {};
  let total = 0;
  let lastSync = 0;
  snap.forEach((d) => {
    const x = d.data();
    byApp[x.appName] = (byApp[x.appName] || 0) + x.seconds;
    daily[x.date] = (daily[x.date] || 0) + x.seconds;
    total += x.seconds;
    const t = x.updatedAt && x.updatedAt.toMillis ? x.updatedAt.toMillis() : 0;
    if (t > lastSync) lastSync = t;
  });
  return { totalSeconds: total, byApp, daily, lastSync };
}

async function usersByEmail(emails) {
  const found = {};
  for (let i = 0; i < emails.length; i += 30) {
    const chunk = emails.slice(i, i + 30);
    const snap = await db.collection("users").where("email", "in", chunk).get();
    snap.forEach((d) => (found[d.data().email] = d.id));
  }
  return found;
}

// ---- routes ---------------------------------------------------------------
app.get("/api/health", (req, res) => res.json({ ok: true }));

// Teacher attaches the report to a Classroom assignment/announcement/material.
app.post("/api/attach", wrap(async (req, res) => {
  await verifyGoogleToken(req);
  const { courseId, itemId, itemType, addOnToken } = req.body || {};
  if (!courseId || !itemId || !addOnToken) throw new HttpError(400, "Missing courseId, itemId or addOnToken");
  const seg = ITEM_SEGMENTS[itemType || "courseWork"];
  if (!seg) throw new HttpError(400, "Unsupported itemType");
  await requireTeacher(req.token, courseId);

  const base = process.env.BASE_URL || `https://${req.get("x-forwarded-host") || req.hostname}`;
  const url = `${CLASSROOM}/courses/${enc(courseId)}/${seg}/${enc(itemId)}/addOnAttachments?addOnToken=${enc(addOnToken)}`;
  const r = await fetch(url, {
    method: "POST",
    headers: { Authorization: `Bearer ${req.token}`, "Content-Type": "application/json" },
    body: JSON.stringify({
      title: "GCR Eco AI usage report",
      teacherViewUri: { uri: `${base}/addon/teacher.html` },
      studentViewUri: { uri: `${base}/addon/student.html` },
    }),
  });
  const data = await r.json().catch(() => ({}));
  if (!r.ok) throw new HttpError(r.status, data.error?.message || "Could not create attachment");
  res.json({ ok: true, attachmentId: data.id });
}));

// Teacher: class report. Authorised only if Classroom says they teach the course.
app.get("/api/report", wrap(async (req, res) => {
  await verifyGoogleToken(req);
  const courseId = req.query.courseId;
  if (!courseId) throw new HttpError(400, "Missing courseId");
  await requireTeacher(req.token, courseId);

  const roster = await listStudents(req.token, courseId);
  const rows = roster.map((s) => ({
    name: s.profile?.name?.fullName || "Student",
    email: (s.profile?.emailAddress || "").toLowerCase(),
  }));
  const uidByEmail = await usersByEmail(rows.map((r) => r.email).filter(Boolean));
  const since = sinceDate();

  const students = await Promise.all(
    rows.map(async (r) => {
      const uid = uidByEmail[r.email];
      if (!uid) return { ...r, installed: false, totalSeconds: 0, byApp: {}, daily: {}, lastSync: 0 };
      return { ...r, installed: true, ...(await usageForUid(uid, since)) };
    })
  );
  students.sort((a, b) => b.totalSeconds - a.totalSeconds);
  res.json({
    since,
    rosterCount: students.length,
    installedCount: students.filter((s) => s.installed).length,
    classTotalSeconds: students.reduce((n, s) => n + s.totalSeconds, 0),
    students,
  });
}));

// Student: their own usage only (identity comes from the verified Google token).
app.get("/api/mine", wrap(async (req, res) => {
  await verifyGoogleToken(req);
  if (!req.googleEmail) throw new HttpError(400, "Email scope required");
  const map = await usersByEmail([req.googleEmail]);
  const uid = map[req.googleEmail];
  if (!uid) return res.json({ installed: false });
  res.json({ installed: true, since: sinceDate(), ...(await usageForUid(uid, sinceDate())) });
}));

exports.api = onRequest({ region: "us-central1", cors: false }, app);
