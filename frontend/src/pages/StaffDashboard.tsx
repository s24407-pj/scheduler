import { useState, useEffect } from 'react';
import { useAuth } from '../AuthContext';
import api from '../api';

interface UserProfile {
  id: number;
  phoneNumber: string;
  firstName: string;
  lastName: string;
  email: string | null;
}

interface Service {
  id: number;
  name: string;
  durationMinutes: number;
  price: number;
}

export default function StaffDashboard() {
  const { role } = useAuth();
  const [profile, setProfile] = useState<UserProfile | null>(null);
  const [services, setServices] = useState<Service[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    Promise.all([
      api.get('/users/me').catch(() => null),
      api.get('/offerings/company/1').catch(() => null),
    ]).then(([profileRes, servicesRes]) => {
      if (profileRes) setProfile(profileRes.data);
      if (servicesRes) setServices(servicesRes.data);
      setLoading(false);
    });
  }, []);

  if (loading) {
    return <div className="text-center text-gray-400 py-12">Ładowanie...</div>;
  }

  return (
    <div className="max-w-4xl mx-auto">
      <h2 className="text-2xl font-bold text-gray-800 mb-6">🏠 Panel pracownika</h2>

      <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
        {/* Profile card */}
        <div className="bg-white rounded-2xl shadow-xl p-6">
          <h3 className="text-lg font-semibold text-gray-800 mb-4">Twój profil</h3>
          {profile && (
            <div className="space-y-2 text-sm">
              <p><span className="text-gray-500">Imię:</span> <span className="font-medium">{profile.firstName} {profile.lastName}</span></p>
              <p><span className="text-gray-500">Telefon:</span> <span className="font-medium">{profile.phoneNumber}</span></p>
              <p><span className="text-gray-500">Email:</span> <span className="font-medium">{profile.email || '—'}</span></p>
              <p><span className="text-gray-500">Rola:</span> <span className="font-medium capitalize">{role}</span></p>
            </div>
          )}
        </div>

        {/* Quick stats */}
        <div className="bg-white rounded-2xl shadow-xl p-6">
          <h3 className="text-lg font-semibold text-gray-800 mb-4">Usługi w ofercie</h3>
          {services.length === 0 ? (
            <p className="text-gray-400 text-sm">Brak usług</p>
          ) : (
            <div className="space-y-2">
              {services.map((s) => (
                <div key={s.id} className="flex justify-between items-center py-2 border-b border-gray-100 last:border-0">
                  <span className="text-gray-700 text-sm">{s.name}</span>
                  <span className="text-gray-500 text-xs">{s.durationMinutes} min · {s.price} zł</span>
                </div>
              ))}
            </div>
          )}
        </div>
      </div>

      {/* Navigation hints */}
      <div className="mt-8 bg-indigo-50 rounded-2xl p-6">
        <h3 className="font-semibold text-indigo-800 mb-2">Szybkie akcje</h3>
        <ul className="text-sm text-indigo-700 space-y-1">
          <li>📋 <a href="/services" className="underline hover:no-underline">Zarządzaj usługami</a> — dodawaj, edytuj, usuwaj usługi</li>
          <li>📅 <a href="/schedule" className="underline hover:no-underline">Harmonogram</a> — przeglądaj swoje wizyty</li>
        </ul>
      </div>
    </div>
  );
}

