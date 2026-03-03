import { useState, useEffect } from 'react';
import api from '../api';

interface Employee {
  id: number;
  userId: number;
  firstName: string;
  lastName: string;
  role: string;
  photoUrl?: string;
}

interface WorkScheduleEntry {
  dayOfWeek: string;
  startTime: string;
  endTime: string;
}

interface Service {
  id: number;
  name: string;
  durationMinutes: number;
  price: number;
  active: boolean;
}

interface ServiceAssignment {
  offeringId: number;
  serviceName: string;
  durationMinutes: number;
  price: number;
}

const DAYS: { key: string; label: string }[] = [
  { key: 'MONDAY',    label: 'Poniedziałek' },
  { key: 'TUESDAY',   label: 'Wtorek' },
  { key: 'WEDNESDAY', label: 'Środa' },
  { key: 'THURSDAY',  label: 'Czwartek' },
  { key: 'FRIDAY',    label: 'Piątek' },
  { key: 'SATURDAY',  label: 'Sobota' },
  { key: 'SUNDAY',    label: 'Niedziela' },
];

function toInput(t: string): string {
  return t ? t.slice(0, 5) : '';
}

function fromInput(t: string): string {
  return t ? `${t}:00` : '';
}

const emptySchedule = () =>
  Object.fromEntries(DAYS.map((d) => [d.key, { enabled: false, start: '09:00', end: '17:00' }]));

type Tab = 'schedule' | 'assignments' | 'photo';

export default function EmployeeManagement() {
  const [employees, setEmployees] = useState<Employee[]>([]);
  const [loadingEmployees, setLoadingEmployees] = useState(true);
  const [selected, setSelected] = useState<Employee | null>(null);
  const [tab, setTab] = useState<Tab>('schedule');
  const [error, setError] = useState('');
  const [success, setSuccess] = useState('');

  // Work schedule
  const [schedule, setSchedule] = useState<Record<string, { enabled: boolean; start: string; end: string }>>(emptySchedule);
  const [loadingSchedule, setLoadingSchedule] = useState(false);
  const [savingSchedule, setSavingSchedule] = useState(false);

  // Service assignments
  const [assignments, setAssignments] = useState<ServiceAssignment[]>([]);
  const [allServices, setAllServices] = useState<Service[]>([]);
  const [loadingAssignments, setLoadingAssignments] = useState(false);

  // Photo
  const [photoPreview, setPhotoPreview] = useState<string | null>(null);
  const [uploadingPhoto, setUploadingPhoto] = useState(false);

  const resetMessages = () => { setError(''); setSuccess(''); };

  useEffect(() => {
    api.get('/company/employees')
      .then((res) => setEmployees(res.data))
      .catch(() => setError('Nie udało się pobrać listy pracowników'))
      .finally(() => setLoadingEmployees(false));
  }, []);

  const handleSelect = (emp: Employee) => {
    resetMessages();
    setSelected(emp);
    setTab('schedule');
    setSchedule(emptySchedule());
    setAssignments([]);
    setPhotoPreview(null);
  };

  // Load work schedule
  useEffect(() => {
    if (!selected || tab !== 'schedule') return;
    setLoadingSchedule(true);
    resetMessages();
    api.get(`/employees/${selected.userId}/work-schedule`)
      .then((res) => {
        const entries: WorkScheduleEntry[] = res.data;
        setSchedule(() => {
          const updated = emptySchedule();
          entries.forEach((e) => {
            updated[e.dayOfWeek] = {
              enabled: true,
              start: toInput(e.startTime),
              end: toInput(e.endTime),
            };
          });
          return updated;
        });
      })
      .catch(() => setError('Nie udało się pobrać grafiku'))
      .finally(() => setLoadingSchedule(false));
  }, [selected, tab]);

  // Load service assignments
  useEffect(() => {
    if (!selected || tab !== 'assignments') return;
    setLoadingAssignments(true);
    resetMessages();
    Promise.all([
      api.get(`/employees/${selected.userId}/offerings`),
      api.get('/offerings/company/1'),
    ])
      .then(([assignRes, servicesRes]) => {
        setAssignments(assignRes.data);
        setAllServices(servicesRes.data.filter((s: Service) => s.active));
      })
      .catch(() => setError('Nie udało się pobrać przypisań usług'))
      .finally(() => setLoadingAssignments(false));
  }, [selected, tab]);

  const handleSaveSchedule = async () => {
    if (!selected) return;
    setSavingSchedule(true);
    resetMessages();
    const entries = DAYS.filter((d) => schedule[d.key].enabled).map((d) => ({
      dayOfWeek: d.key,
      startTime: fromInput(schedule[d.key].start),
      endTime: fromInput(schedule[d.key].end),
    }));
    try {
      await api.put(`/employees/${selected.userId}/work-schedule`, { entries });
      setSuccess('Grafik zapisany!');
    } catch (err: any) {
      if (err.response?.status === 403) {
        setError('Brak uprawnień — tylko właściciel może ustawiać grafiki.');
      } else {
        setError(err.response?.data?.message || 'Błąd zapisywania grafiku');
      }
    } finally {
      setSavingSchedule(false);
    }
  };

  const toggleDay = (day: string) =>
    setSchedule((prev) => ({ ...prev, [day]: { ...prev[day], enabled: !prev[day].enabled } }));

  const updateDayTime = (day: string, field: 'start' | 'end', value: string) =>
    setSchedule((prev) => ({ ...prev, [day]: { ...prev[day], [field]: value } }));

  const isAssigned = (serviceId: number) => assignments.some((a) => a.offeringId === serviceId);

  const handleAssign = async (serviceId: number) => {
    if (!selected) return;
    resetMessages();
    try {
      await api.post(`/employees/${selected.userId}/offerings/${serviceId}`);
      const res = await api.get(`/employees/${selected.userId}/offerings`);
      setAssignments(res.data);
      setSuccess('Usługa przypisana');
    } catch (err: any) {
      if (err.response?.status === 403) {
        setError('Brak uprawnień — tylko właściciel może zarządzać przypisaniami.');
      } else {
        setError(err.response?.data?.message || 'Błąd przypisywania');
      }
    }
  };

  const handleRemove = async (serviceId: number) => {
    if (!selected) return;
    resetMessages();
    try {
      await api.delete(`/employees/${selected.userId}/offerings/${serviceId}`);
      setAssignments((prev) => prev.filter((a) => a.offeringId !== serviceId));
      setSuccess('Przypisanie usunięte');
    } catch (err: any) {
      setError(err.response?.data?.message || 'Błąd usuwania przypisania');
    }
  };

  const handlePhotoUpload = async (e: React.ChangeEvent<HTMLInputElement>) => {
    if (!selected || !e.target.files?.[0]) return;
    const file = e.target.files[0];
    setUploadingPhoto(true);
    resetMessages();
    const formData = new FormData();
    formData.append('photo', file);
    try {
      const res = await api.post(`/employees/${selected.userId}/photo`, formData, {
        headers: { 'Content-Type': 'multipart/form-data' },
      });
      setSelected((prev) => prev ? { ...prev, photoUrl: res.data.photoUrl } : prev);
      setEmployees((prev) => prev.map((emp) =>
        emp.userId === selected.userId ? { ...emp, photoUrl: res.data.photoUrl } : emp
      ));
      setPhotoPreview(res.data.photoUrl);
      setSuccess('Zdjęcie zapisane');
    } catch (err: any) {
      setError(err.response?.data?.message || 'Błąd uploadu zdjęcia');
    } finally {
      setUploadingPhoto(false);
      e.target.value = '';
    }
  };

  const handlePhotoDelete = async () => {
    if (!selected) return;
    resetMessages();
    try {
      await api.delete(`/employees/${selected.userId}/photo`);
      setSelected((prev) => prev ? { ...prev, photoUrl: undefined } : prev);
      setEmployees((prev) => prev.map((emp) =>
        emp.userId === selected.userId ? { ...emp, photoUrl: undefined } : emp
      ));
      setPhotoPreview(null);
      setSuccess('Zdjęcie usunięte');
    } catch (err: any) {
      setError(err.response?.data?.message || 'Błąd usuwania zdjęcia');
    }
  };

  return (
    <div className="max-w-3xl mx-auto space-y-6">
      <h2 className="text-2xl font-bold text-gray-800">👥 Zarządzanie pracownikami</h2>

      {error && (
        <div className="bg-red-50 border border-red-200 text-red-700 px-4 py-3 rounded-lg">{error}</div>
      )}
      {success && (
        <div className="bg-green-50 border border-green-200 text-green-700 px-4 py-3 rounded-lg">{success}</div>
      )}

      {/* Employee list */}
      <div className="bg-white rounded-2xl shadow-xl p-6">
        <h3 className="text-base font-semibold text-gray-700 mb-3">Wybierz pracownika</h3>
        {loadingEmployees ? (
          <div className="text-gray-400 text-sm">Ładowanie...</div>
        ) : employees.length === 0 ? (
          <div className="text-gray-400 text-sm">Brak pracowników w firmie.</div>
        ) : (
          <div className="space-y-2">
            {employees.map((emp) => (
              <button
                key={emp.id}
                onClick={() => handleSelect(emp)}
                className={`w-full flex items-center justify-between px-4 py-3 rounded-xl border transition text-left ${
                  selected?.id === emp.id
                    ? 'border-indigo-400 bg-indigo-50'
                    : 'border-gray-200 hover:border-indigo-300 hover:bg-gray-50'
                }`}
              >
                <div className="flex items-center gap-3">
                  {emp.photoUrl ? (
                    <img src={emp.photoUrl} alt="" className="w-8 h-8 rounded-full object-cover" />
                  ) : (
                    <div className="w-8 h-8 rounded-full bg-gray-200 flex items-center justify-center text-gray-400 text-sm">
                      👤
                    </div>
                  )}
                  <span className="font-medium text-gray-800">
                    {emp.firstName} {emp.lastName}
                  </span>
                </div>
                <span className={`text-xs px-2 py-0.5 rounded-full font-medium ${
                  emp.role === 'OWNER'
                    ? 'bg-purple-100 text-purple-700'
                    : 'bg-gray-100 text-gray-600'
                }`}>
                  {emp.role === 'OWNER' ? 'Właściciel' : 'Pracownik'}
                </span>
              </button>
            ))}
          </div>
        )}
      </div>

      {selected && (
        <>
          {/* Tabs */}
          <div className="flex gap-1 bg-gray-100 rounded-xl p-1">
            <button
              onClick={() => { resetMessages(); setTab('schedule'); }}
              className={`flex-1 py-2 rounded-lg text-sm font-medium transition ${
                tab === 'schedule' ? 'bg-white text-indigo-700 shadow' : 'text-gray-500 hover:text-gray-700'
              }`}
            >
              📅 Grafik tygodniowy
            </button>
            <button
              onClick={() => { resetMessages(); setTab('assignments'); }}
              className={`flex-1 py-2 rounded-lg text-sm font-medium transition ${
                tab === 'assignments' ? 'bg-white text-indigo-700 shadow' : 'text-gray-500 hover:text-gray-700'
              }`}
            >
              🔧 Przypisane usługi
            </button>
            <button
              onClick={() => { resetMessages(); setTab('photo'); setPhotoPreview(selected?.photoUrl ?? null); }}
              className={`flex-1 py-2 rounded-lg text-sm font-medium transition ${
                tab === 'photo' ? 'bg-white text-indigo-700 shadow' : 'text-gray-500 hover:text-gray-700'
              }`}
            >
              📷 Zdjęcie
            </button>
          </div>

          {/* Work Schedule Tab */}
          {tab === 'schedule' && (
            <div className="bg-white rounded-2xl shadow-xl p-6">
              <h3 className="text-base font-semibold text-gray-700 mb-4">
                Grafik — {selected.firstName} {selected.lastName}
              </h3>
              {loadingSchedule ? (
                <div className="text-gray-400 text-center py-4">Ładowanie...</div>
              ) : (
                <div className="space-y-3">
                  {DAYS.map((d) => (
                    <div key={d.key} className="flex items-center gap-3">
                      <input
                        type="checkbox"
                        id={`day-${d.key}`}
                        checked={schedule[d.key].enabled}
                        onChange={() => toggleDay(d.key)}
                        className="w-4 h-4 accent-indigo-600"
                      />
                      <label
                        htmlFor={`day-${d.key}`}
                        className={`w-32 text-sm font-medium ${schedule[d.key].enabled ? 'text-gray-800' : 'text-gray-400'}`}
                      >
                        {d.label}
                      </label>
                      {schedule[d.key].enabled ? (
                        <div className="flex items-center gap-2">
                          <input
                            type="time"
                            value={schedule[d.key].start}
                            onChange={(e) => updateDayTime(d.key, 'start', e.target.value)}
                            className="border border-gray-300 rounded-lg px-3 py-1.5 text-sm focus:ring-2 focus:ring-indigo-500 outline-none"
                          />
                          <span className="text-gray-400 text-sm">–</span>
                          <input
                            type="time"
                            value={schedule[d.key].end}
                            onChange={(e) => updateDayTime(d.key, 'end', e.target.value)}
                            className="border border-gray-300 rounded-lg px-3 py-1.5 text-sm focus:ring-2 focus:ring-indigo-500 outline-none"
                          />
                        </div>
                      ) : (
                        <span className="text-xs text-gray-400">nie pracuje</span>
                      )}
                    </div>
                  ))}
                  <button
                    onClick={handleSaveSchedule}
                    disabled={savingSchedule}
                    className="mt-4 w-full bg-indigo-600 text-white py-2 rounded-lg hover:bg-indigo-700 disabled:opacity-50 transition font-medium"
                  >
                    {savingSchedule ? 'Zapisywanie...' : 'Zapisz grafik'}
                  </button>
                </div>
              )}
            </div>
          )}

          {/* Photo Tab */}
          {tab === 'photo' && (
            <div className="bg-white rounded-2xl shadow-xl p-6">
              <h3 className="text-base font-semibold text-gray-700 mb-4">
                Zdjęcie profilowe — {selected.firstName} {selected.lastName}
              </h3>
              <div className="flex flex-col items-center gap-4">
                {photoPreview ? (
                  <img
                    src={photoPreview}
                    alt="Zdjęcie pracownika"
                    className="w-32 h-32 rounded-full object-cover border-2 border-indigo-200"
                  />
                ) : (
                  <div className="w-32 h-32 rounded-full bg-gray-100 flex items-center justify-center text-4xl text-gray-400 border-2 border-dashed border-gray-300">
                    👤
                  </div>
                )}
                <label className={`cursor-pointer bg-indigo-600 text-white px-4 py-2 rounded-lg hover:bg-indigo-700 transition font-medium text-sm ${uploadingPhoto ? 'opacity-50 pointer-events-none' : ''}`}>
                  {uploadingPhoto ? 'Uploading...' : photoPreview ? 'Zmień zdjęcie' : 'Wgraj zdjęcie'}
                  <input
                    type="file"
                    accept="image/jpeg,image/png,image/webp"
                    className="hidden"
                    onChange={handlePhotoUpload}
                    disabled={uploadingPhoto}
                  />
                </label>
                {photoPreview && (
                  <button
                    onClick={handlePhotoDelete}
                    className="text-sm text-red-500 hover:text-red-700 transition"
                  >
                    Usuń zdjęcie
                  </button>
                )}
                <p className="text-xs text-gray-400 text-center">JPEG, PNG lub WebP · max 5 MB</p>
              </div>
            </div>
          )}

          {/* Service Assignments Tab */}
          {tab === 'assignments' && (
            <div className="bg-white rounded-2xl shadow-xl p-6">
              <h3 className="text-base font-semibold text-gray-700 mb-1">
                Usługi — {selected.firstName} {selected.lastName}
              </h3>
              <p className="text-xs text-gray-400 mb-4">
                Brak przypisań = pracownik może wykonywać wszystkie usługi. Przypisz przynajmniej jedną, by ograniczyć.
              </p>
              {loadingAssignments ? (
                <div className="text-gray-400 text-center py-4">Ładowanie...</div>
              ) : (
                <div className="space-y-2">
                  {allServices.length === 0 && (
                    <p className="text-gray-400 text-sm">Brak aktywnych usług w firmie.</p>
                  )}
                  {allServices.map((s) => {
                    const assigned = isAssigned(s.id);
                    return (
                      <div
                        key={s.id}
                        className={`flex items-center justify-between p-3 rounded-lg border ${
                          assigned ? 'border-indigo-200 bg-indigo-50' : 'border-gray-200 bg-white'
                        }`}
                      >
                        <div>
                          <span className="font-medium text-sm text-gray-800">{s.name}</span>
                          <span className="text-xs text-gray-500 ml-2">
                            {s.durationMinutes} min · {s.price} zł
                          </span>
                        </div>
                        <button
                          onClick={() => assigned ? handleRemove(s.id) : handleAssign(s.id)}
                          className={`text-sm font-medium px-3 py-1 rounded-lg transition ${
                            assigned
                              ? 'text-red-500 hover:bg-red-50'
                              : 'text-indigo-600 hover:bg-indigo-100'
                          }`}
                        >
                          {assigned ? 'Usuń' : 'Przypisz'}
                        </button>
                      </div>
                    );
                  })}
                </div>
              )}
            </div>
          )}
        </>
      )}
    </div>
  );
}
