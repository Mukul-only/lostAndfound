You are a senior Android developer. Build a polished native Android app for a college-campus Lost & Found project.

First inspect the existing repository, build configuration, language, and app structure. Preserve the project's existing language and conventions where practical. If this is a new project, use Java. Use the traditional Android Views system with XML layouts and Activities (Fragments are acceptable for top-level navigation). Do not use Jetpack Compose.

Do not jump straight into coding. First give me:
1. A concise implementation plan.
2. The proposed screen/navigation map.
3. The data model and backend structure.
4. Any setup I must perform, such as creating a Firebase project.
Then implement the plan. If a decision is not specified below, choose the simplest reasonable approach, state the assumption, and continue.

PRODUCT GOAL
Help students on one college campus report lost or found items, identify potential matches, verify ownership, chat privately, and arrange a safe return.

This is a student project, but it should feel like a coherent, working application—not a UI-only mockup.

USERS
- Students can both lose and find items; these are report types, not separate account roles.
- Do not build a full admin dashboard in the first version.
- Include a basic action to report an inappropriate post/user, with the data stored for possible future moderation.

CORE NAVIGATION
- Authentication: sign in and register.
- Home: prominent “I lost something” and “I found something” actions, search, and recent reports.
- Browse/Search: Lost and Found tabs or filters; filter by category and campus location; search by keywords.
- Item Details: public item information and the appropriate response action.
- Create/Edit Report: separate form behavior for Lost and Found reports.
- My Reports: view and manage the user's own posts and responses.
- Chats: conversation list and message screen.
- Profile: basic account details and sign out.

LOST-ITEM FLOW
1. A student searches Found reports.
2. If there is no match, they create a Lost report with title, category, description, optional photo, approximate last-seen campus location, and approximate date/time.
3. Another student can tap “I found this” on that report and submit an initial private response explaining what they found.
4. The report owner reviews the response. If they accept it, a private chat opens.
5. They arrange a handover at a public campus location or designated lost-and-found desk.
6. The report owner marks the report Returned.

FOUND-ITEM FLOW
1. A student searches Lost reports.
2. If there is no match, they create a Found report with title, category, public description, optional photo, approximate found location, and date/time.
3. The finder supplies a private verification question or identifying detail that is NOT shown on the public item page.
4. A potential owner taps “This is mine” and submits a private answer/evidence.
5. The finder reviews claims and accepts or rejects one. Only an accepted claimant gains access to chat.
6. They arrange a safe handover; the finder marks the report Returned.

A found report may receive multiple pending claims, but only one can be accepted at a time. Rejecting one claim must not close the report. Users must not be able to respond to their own reports or create duplicate responses to the same report.

REPORT STATUS
Keep the state model understandable: OPEN, HANDOVER_ARRANGED, RETURNED, and CLOSED. Claims/responses have separate PENDING, ACCEPTED, and REJECTED states. Define and enforce valid transitions. Show useful empty, loading, error, and offline states.

PRIVACY AND SAFETY
- Never expose verification answers publicly.
- Do not require students to publish phone numbers, exact dorm-room locations, live locations, ID numbers, or complete identifying details of found items.
- Use approximate campus locations and encourage handovers in public places.
- Only the report owner can edit/close their report or accept/reject its responses.
- Only the two participants in an accepted response can read and send messages in its conversation.
- Include a simple “Report this post” action.
- Do not treat a matching photo or keyword as proof of ownership.

TECHNICAL DIRECTION
- Traditional native Android: XML layouts, Activities/Fragments, RecyclerView, Material components, ViewBinding, and a maintainable repository/data-access layer.
- Use the existing project language; for a new project, use Java.
- Use Firebase Authentication for accounts, Cloud Firestore for reports/responses/conversations/messages, and Firebase Storage for optional images, unless the repository already has a suitable backend. Explain any deviation.
- Add Firestore and Storage security rules covering ownership, private claims, accepted-chat access, and image access as appropriate. Do not rely on UI checks alone.
- Never commit credentials or pretend Firebase is configured when it is not. Provide exact setup steps for google-services.json, Firebase services, indexes if needed, and test accounts.
- Use server timestamps where appropriate. Handle asynchronous operations, upload failures, duplicate taps, and users navigating away during a request.
- Keep the architecture proportional to a student project; avoid unnecessary abstractions or features.

DESIGN QUALITY
Make the UI clean, accessible, and consistent: clear typography, spacing, color hierarchy, meaningful icons, readable forms, validation messages, and polished empty states. Use a campus-friendly visual style. Support reasonable screen sizes, keyboard behavior, back navigation, and dark mode if practical. Do not use placeholder buttons that appear functional but do nothing.

DELIVERABLES
1. Working source code and XML resources.
2. Firebase security rules and any required indexes.
3. A short README with setup, build/run instructions, architecture, and known limitations.
4. A manual test checklist covering both lost and found journeys.
5. Tests for important validation and state-transition logic where feasible.
6. A final summary of what was implemented, what requires my Firebase setup, and anything intentionally deferred.

IMPLEMENTATION ORDER
Build in vertical slices so the app remains runnable:
1. Project setup, navigation, and authentication.
2. Create/list/search/detail/edit reports.
3. Private responses and claim review.
4. Accepted-response chat.
5. Return/close flow, safety reporting, polish, and tests.

After implementation, run the available build and tests. Fix errors you can verify. If Firebase configuration is unavailable, distinguish clearly between code that builds locally and flows that still require live Firebase testing.

Do not add AI matching, maps, push notifications, or a full admin portal in this first version. Simple category/location/date/keyword filtering is sufficient.
